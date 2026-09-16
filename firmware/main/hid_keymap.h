/* Human-readable key names to HID usages.
 *
 * Profiles are stored with names like "Ctrl+Shift+P" rather than raw usage
 * bytes: exported JSON stays legible, and a profile authored against one
 * firmware build keeps working against the next. Names are resolved once when a
 * profile is compiled, not on every keystroke. */
#pragma once

#include <stdbool.h>
#include <stdint.h>

/* HID modifier bitmask. */
enum {
    DECK_MOD_LCTRL  = 0x01,
    DECK_MOD_LSHIFT = 0x02,
    DECK_MOD_LALT   = 0x04,
    DECK_MOD_LGUI   = 0x08,
    DECK_MOD_RCTRL  = 0x10,
    DECK_MOD_RSHIFT = 0x20,
    DECK_MOD_RALT   = 0x40,
    DECK_MOD_RGUI   = 0x80,
};

/* Parse a chord such as "Ctrl+Shift+P", "F13", "Numpad7" or "Meta+Left".
 * A chord may be modifiers only ("Ctrl+Shift"), in which case *usage is 0.
 * Returns false if the key name is unknown. */
bool hid_keymap_parse(const char *chord, uint8_t *mods, uint8_t *usage);

/* Map one ASCII character to the keystroke that produces it on a US layout.
 * Returns false for characters that cannot be typed (including non-ASCII). */
bool hid_keymap_ascii(char c, uint8_t *mods, uint8_t *usage);

/* Consumer-page usage for a media key name such as "play_pause". */
bool hid_keymap_media(const char *name, uint16_t *usage);

/* Mouse button name ("left", "middle", …) to the DECK_BTN_* bitmask. */
bool hid_keymap_button(const char *name, uint8_t *mask);
