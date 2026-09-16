#include "hid_keymap.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

#include "class/hid/hid.h"
#include "usb_hid.h"

/* TinyUSB ships the US-layout ASCII table; reusing it avoids maintaining a
 * second copy that could drift. Each entry is {shifted, keycode}. */
static const uint8_t s_ascii[128][2] = {HID_ASCII_TO_KEYCODE};

struct name_usage {
    const char *name;
    uint8_t usage;
};

/* Ordered roughly by how often a profile mentions them. Lookup is a linear
 * scan, which happens only at profile-compile time. */
static const struct name_usage s_keys[] = {
    /* Letters and digits are handled programmatically below, so this table is
     * only the named keys. */
    {"return", 0x28}, {"enter", 0x28}, {"escape", 0x29}, {"esc", 0x29},
    {"backspace", 0x2a}, {"bksp", 0x2a}, {"tab", 0x2b}, {"space", 0x2c},
    {"minus", 0x2d}, {"-", 0x2d}, {"equal", 0x2e}, {"=", 0x2e},
    {"bracketleft", 0x2f}, {"[", 0x2f}, {"bracketright", 0x30}, {"]", 0x30},
    {"backslash", 0x31}, {"\\", 0x31}, {"semicolon", 0x33}, {";", 0x33},
    {"apostrophe", 0x34}, {"'", 0x34}, {"quote", 0x34},
    {"grave", 0x35}, {"`", 0x35}, {"tilde", 0x35},
    {"comma", 0x36}, {",", 0x36}, {"period", 0x37}, {".", 0x37},
    {"slash", 0x38}, {"/", 0x38}, {"capslock", 0x39},

    {"printscreen", 0x46}, {"prtsc", 0x46}, {"sysreq", 0x46},
    {"scrolllock", 0x47}, {"pause", 0x48}, {"break", 0x48},
    {"insert", 0x49}, {"ins", 0x49}, {"home", 0x4a},
    {"pageup", 0x4b}, {"pgup", 0x4b}, {"prior", 0x4b},
    {"delete", 0x4c}, {"del", 0x4c}, {"end", 0x4d},
    {"pagedown", 0x4e}, {"pgdn", 0x4e}, {"next", 0x4e},
    {"right", 0x4f}, {"left", 0x50}, {"down", 0x51}, {"up", 0x52},

    {"numlock", 0x53},
    {"numpaddivide", 0x54}, {"kpdivide", 0x54},
    {"numpadmultiply", 0x55}, {"kpmultiply", 0x55},
    {"numpadsubtract", 0x56}, {"kpminus", 0x56}, {"numpadminus", 0x56},
    {"numpadadd", 0x57}, {"kpplus", 0x57}, {"numpadplus", 0x57},
    /* "+" is deliberately the keypad plus: it needs no shift, so a chord like
     * "Ctrl++" cannot accidentally become Ctrl+Shift+Equal. */
    {"plus", 0x57}, {"+", 0x57},
    {"numpadenter", 0x58}, {"kpenter", 0x58},
    {"numpaddecimal", 0x63}, {"kpdot", 0x63}, {"numpaddot", 0x63},
    {"numpadequal", 0x67}, {"kpequal", 0x67},

    {"application", 0x65}, {"menu", 0x65}, {"context", 0x65},
    {"power", 0x66},
    {"execute", 0x74}, {"help", 0x75}, {"select", 0x77}, {"stop", 0x78},
    {"again", 0x79}, {"undo", 0x7a}, {"cut", 0x7b}, {"copy", 0x7c},
    {"paste", 0x7d}, {"find", 0x7e},
};

struct name_mod {
    const char *name;
    uint8_t mod;
};

static const struct name_mod s_mods[] = {
    {"ctrl", DECK_MOD_LCTRL}, {"control", DECK_MOD_LCTRL}, {"lctrl", DECK_MOD_LCTRL},
    {"shift", DECK_MOD_LSHIFT}, {"lshift", DECK_MOD_LSHIFT},
    {"alt", DECK_MOD_LALT}, {"option", DECK_MOD_LALT}, {"lalt", DECK_MOD_LALT},
    {"meta", DECK_MOD_LGUI}, {"super", DECK_MOD_LGUI}, {"win", DECK_MOD_LGUI},
    {"cmd", DECK_MOD_LGUI}, {"gui", DECK_MOD_LGUI}, {"lgui", DECK_MOD_LGUI},
    {"rctrl", DECK_MOD_RCTRL}, {"rshift", DECK_MOD_RSHIFT},
    {"ralt", DECK_MOD_RALT}, {"altgr", DECK_MOD_RALT},
    {"rmeta", DECK_MOD_RGUI}, {"rsuper", DECK_MOD_RGUI}, {"rwin", DECK_MOD_RGUI},
};

struct name_media {
    const char *name;
    uint16_t usage;
};

static const struct name_media s_media[] = {
    {"play_pause", 0x00cd}, {"play", 0x00b0}, {"pause", 0x00b1},
    {"stop", 0x00b7}, {"next", 0x00b5}, {"prev", 0x00b6},
    {"vol_up", 0x00e9}, {"vol_down", 0x00ea}, {"mute", 0x00e2},
    {"record", 0x00b2}, {"rewind", 0x00b4}, {"fast_forward", 0x00b3},
    {"eject", 0x00b8},
    {"bright_up", 0x006f}, {"bright_down", 0x0070},
    {"launch_mail", 0x018a}, {"launch_calc", 0x0192},
    {"launch_files", 0x0194}, {"launch_browser", 0x0196},
    {"search", 0x0221}, {"home", 0x0223},
    {"back", 0x0224}, {"forward", 0x0225}, {"refresh", 0x0227},
};

struct name_btn {
    const char *name;
    uint8_t mask;
};

static const struct name_btn s_buttons[] = {
    {"left", DECK_BTN_LEFT}, {"right", DECK_BTN_RIGHT}, {"middle", DECK_BTN_MIDDLE},
    {"back", DECK_BTN_BACK}, {"forward", DECK_BTN_FORWARD},
};

/* Case-insensitive compare that also ignores spaces and underscores, so
 * "Page Up", "pageup" and "page_up" all land on the same key. */
static bool loose_eq(const char *a, const char *b)
{
    while (*a && *b) {
        while (*a == ' ' || *a == '_') a++;
        while (*b == ' ' || *b == '_') b++;
        if (!*a || !*b) break;
        if (tolower((unsigned char)*a) != tolower((unsigned char)*b)) return false;
        a++; b++;
    }
    while (*a == ' ' || *a == '_') a++;
    while (*b == ' ' || *b == '_') b++;
    return *a == 0 && *b == 0;
}

static bool lookup_key(const char *name, uint8_t *mods, uint8_t *usage)
{
    size_t len = strlen(name);
    if (len == 0) {
        return false;
    }

    /* Single printable character: let the ASCII table decide, so "A" and "a"
     * both mean the A key and "?" picks up its shift automatically. */
    if (len == 1) {
        return hid_keymap_ascii(name[0], mods, usage);
    }

    /* Fn keys, including the F13..F24 range a macro pad exists to reach. */
    if ((name[0] == 'f' || name[0] == 'F') && isdigit((unsigned char)name[1])) {
        int n = atoi(name + 1);
        if (n >= 1 && n <= 12) { *usage = (uint8_t)(0x3a + n - 1); return true; }
        if (n >= 13 && n <= 24) { *usage = (uint8_t)(0x68 + n - 13); return true; }
        return false;
    }

    /* Numpad digits: "Numpad7", "KP7", "Keypad7". */
    const char *digits = NULL;
    if (!strncasecmp(name, "numpad", 6)) digits = name + 6;
    else if (!strncasecmp(name, "keypad", 6)) digits = name + 6;
    else if (!strncasecmp(name, "kp", 2)) digits = name + 2;
    if (digits && isdigit((unsigned char)digits[0]) && digits[1] == 0) {
        int n = digits[0] - '0';
        *usage = (n == 0) ? 0x62 : (uint8_t)(0x59 + n - 1);
        return true;
    }

    for (size_t i = 0; i < sizeof(s_keys) / sizeof(s_keys[0]); i++) {
        if (loose_eq(name, s_keys[i].name)) {
            *usage = s_keys[i].usage;
            return true;
        }
    }
    return false;
}

bool hid_keymap_ascii(char c, uint8_t *mods, uint8_t *usage)
{
    unsigned char u = (unsigned char)c;
    if (u >= 128) {
        return false;
    }
    uint8_t kc = s_ascii[u][1];
    if (kc == 0) {
        return false;
    }
    *usage = kc;
    if (s_ascii[u][0]) {
        *mods |= DECK_MOD_LSHIFT;
    }
    return true;
}

bool hid_keymap_parse(const char *chord, uint8_t *mods_out, uint8_t *usage_out)
{
    if (!chord || !*chord) {
        return false;
    }
    *mods_out = 0;
    *usage_out = 0;

    char buf[96];
    strncpy(buf, chord, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = 0;

    /* Trim. */
    char *s = buf;
    while (*s == ' ') s++;
    size_t len = strlen(s);
    while (len && s[len - 1] == ' ') s[--len] = 0;
    if (len == 0) {
        return false;
    }

    /* A trailing '+' is the plus key itself, not a dangling separator: this is
     * what makes "Ctrl++" (zoom in) parse the way its author meant. */
    char *keypart;
    char modpart[96];
    if (len > 1 && s[len - 1] == '+') {
        keypart = "+";
        size_t n = len - 1;
        while (n && s[n - 1] == '+') n--;   /* drop the separator too */
        memcpy(modpart, s, n);
        modpart[n] = 0;
    } else {
        char *last = strrchr(s, '+');
        if (last) {
            keypart = last + 1;
            size_t n = (size_t)(last - s);
            memcpy(modpart, s, n);
            modpart[n] = 0;
        } else {
            keypart = s;
            modpart[0] = 0;
        }
    }

    /* Modifiers. */
    char *save = NULL;
    for (char *tok = strtok_r(modpart, "+", &save); tok; tok = strtok_r(NULL, "+", &save)) {
        while (*tok == ' ') tok++;
        size_t tl = strlen(tok);
        while (tl && tok[tl - 1] == ' ') tok[--tl] = 0;
        if (!tl) continue;
        bool found = false;
        for (size_t i = 0; i < sizeof(s_mods) / sizeof(s_mods[0]); i++) {
            if (loose_eq(tok, s_mods[i].name)) {
                *mods_out |= s_mods[i].mod;
                found = true;
                break;
            }
        }
        if (!found) {
            return false;
        }
    }

    while (*keypart == ' ') keypart++;
    if (!*keypart) {
        return *mods_out != 0;
    }

    /* A modifiers-only chord is legitimate: "Meta+Shift" is a real binding in
     * QindaQt, where holding it arms window docking, and a bare "Meta" opens the
     * launcher. Splitting on the last '+' leaves a modifier name sitting in the
     * key slot, so check for that before giving up on it as a key. */
    for (size_t i = 0; i < sizeof(s_mods) / sizeof(s_mods[0]); i++) {
        if (loose_eq(keypart, s_mods[i].name)) {
            *mods_out |= s_mods[i].mod;
            return true;
        }
    }

    return lookup_key(keypart, mods_out, usage_out);
}

bool hid_keymap_media(const char *name, uint16_t *usage)
{
    if (!name) return false;
    for (size_t i = 0; i < sizeof(s_media) / sizeof(s_media[0]); i++) {
        if (loose_eq(name, s_media[i].name)) {
            *usage = s_media[i].usage;
            return true;
        }
    }
    return false;
}

bool hid_keymap_button(const char *name, uint8_t *mask)
{
    if (!name) {
        *mask = DECK_BTN_LEFT;
        return true;
    }
    for (size_t i = 0; i < sizeof(s_buttons) / sizeof(s_buttons[0]); i++) {
        if (loose_eq(name, s_buttons[i].name)) {
            *mask = s_buttons[i].mask;
            return true;
        }
    }
    return false;
}
