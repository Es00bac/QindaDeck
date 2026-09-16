#include "remap.h"

#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "action.h"
#include "deck_config.h"
#include "esp_hid_common.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "hid_keymap.h"
#include "net_link.h"
#include "usb_hid.h"

static const char *TAG = "remap";

#define REMAP_MAX_BINDINGS 96
#define REMAP_MAX_REPORT   64
#define REMAP_MAX_DEVICES  4

/* Per-device runtime state: the previous report, so changes can be diffed, and
 * one edge bit per binding. Kept out of the stored document because it is
 * meaningless across a reconnect. */
typedef struct {
    char addr[18];
    bool used;
    uint8_t prev[REMAP_MAX_REPORT];
    uint16_t prev_len;
    bool have_prev;
    bool state[REMAP_MAX_BINDINGS];
    /* Keyboard usages we are currently passing through, so the right ones can be
     * released when they stop being held. */
    uint8_t passed[8];
    uint8_t passed_mods;
} dev_state_t;

static dev_state_t s_dev[REMAP_MAX_DEVICES];
static SemaphoreHandle_t s_lock;
static bool s_learn;
static bool s_learn_mute;

/* ------------------------------------------------------------------ storage */

static bool addr_ok(const char *a)
{
    if (!a || strlen(a) != 17) {
        return false;
    }
    for (int i = 0; i < 17; i++) {
        if ((i % 3) == 2) {
            if (a[i] != ':') return false;
        } else if (!isxdigit((unsigned char)a[i])) {
            return false;
        }
    }
    return true;
}

static void path_for(const char *addr, char *out, size_t len)
{
    /* Colons are legal in SPIFFS names but awkward everywhere else. */
    char flat[13];
    int k = 0;
    for (const char *p = addr; *p && k < 12; p++) {
        if (*p != ':') {
            flat[k++] = (char)tolower((unsigned char)*p);
        }
    }
    flat[k] = 0;
    snprintf(out, len, DECK_FS_BASE "/r_%s.json", flat);
}

static char *read_file(const char *path)
{
    FILE *f = fopen(path, "r");
    if (!f) {
        return NULL;
    }
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (len <= 0 || len > 48 * 1024) {
        fclose(f);
        return NULL;
    }
    char *buf = malloc((size_t)len + 1);
    if (!buf) {
        fclose(f);
        return NULL;
    }
    size_t got = fread(buf, 1, (size_t)len, f);
    fclose(f);
    buf[got] = 0;
    return buf;
}

cJSON *remap_get(const char *addr)
{
    if (!addr_ok(addr)) {
        return NULL;
    }
    char path[64];
    path_for(addr, path, sizeof(path));
    char *txt = read_file(path);
    if (!txt) {
        return NULL;
    }
    cJSON *d = cJSON_Parse(txt);
    free(txt);
    return d;
}

static bool validate_src(const cJSON *src, char *err, size_t errlen)
{
    if (!cJSON_IsObject(src)) {
        snprintf(err, errlen, "binding needs a src object");
        return false;
    }
    const cJSON *kind = cJSON_GetObjectItemCaseSensitive(src, "kind");
    if (!cJSON_IsString(kind)) {
        snprintf(err, errlen, "src needs a kind");
        return false;
    }
    const char *k = kind->valuestring;
    const cJSON *byte = cJSON_GetObjectItemCaseSensitive(src, "byte");

    if (!strcmp(k, "btn")) {
        const cJSON *bit = cJSON_GetObjectItemCaseSensitive(src, "bit");
        if (!cJSON_IsNumber(byte) || byte->valueint < 0 || byte->valueint >= REMAP_MAX_REPORT) {
            snprintf(err, errlen, "btn src needs byte 0..%d", REMAP_MAX_REPORT - 1);
            return false;
        }
        if (!cJSON_IsNumber(bit) || bit->valueint < 0 || bit->valueint > 7) {
            snprintf(err, errlen, "btn src needs bit 0..7");
            return false;
        }
        return true;
    }
    if (!strcmp(k, "axis")) {
        const cJSON *thr = cJSON_GetObjectItemCaseSensitive(src, "thr");
        const cJSON *dir = cJSON_GetObjectItemCaseSensitive(src, "dir");
        if (!cJSON_IsNumber(byte) || byte->valueint < 0 || byte->valueint >= REMAP_MAX_REPORT) {
            snprintf(err, errlen, "axis src needs byte 0..%d", REMAP_MAX_REPORT - 1);
            return false;
        }
        if (!cJSON_IsNumber(thr) || thr->valueint < 0 || thr->valueint > 255) {
            snprintf(err, errlen, "axis src needs thr 0..255");
            return false;
        }
        if (!cJSON_IsString(dir) || (strcmp(dir->valuestring, "lo") && strcmp(dir->valuestring, "hi"))) {
            snprintf(err, errlen, "axis src dir must be 'lo' or 'hi'");
            return false;
        }
        return true;
    }
    if (!strcmp(k, "key")) {
        const cJSON *usage = cJSON_GetObjectItemCaseSensitive(src, "usage");
        if (!cJSON_IsNumber(usage) || usage->valueint < 1 || usage->valueint > 255) {
            snprintf(err, errlen, "key src needs a usage 1..255");
            return false;
        }
        return true;
    }
    snprintf(err, errlen, "unknown src kind '%s'", k);
    return false;
}

bool remap_put(const cJSON *doc, char *err, size_t errlen)
{
    if (!cJSON_IsObject(doc)) {
        snprintf(err, errlen, "remap must be an object");
        return false;
    }
    const cJSON *addr = cJSON_GetObjectItemCaseSensitive(doc, "addr");
    if (!cJSON_IsString(addr) || !addr_ok(addr->valuestring)) {
        snprintf(err, errlen, "remap needs an addr like aa:bb:cc:dd:ee:ff");
        return false;
    }
    const cJSON *binds = cJSON_GetObjectItemCaseSensitive(doc, "bindings");
    if (!cJSON_IsArray(binds)) {
        snprintf(err, errlen, "remap needs a bindings array");
        return false;
    }
    int n = cJSON_GetArraySize(binds);
    if (n > REMAP_MAX_BINDINGS) {
        snprintf(err, errlen, "more than %d bindings", REMAP_MAX_BINDINGS);
        return false;
    }
    for (int i = 0; i < n; i++) {
        const cJSON *b = cJSON_GetArrayItem(binds, i);
        char berr[180] = {0};
        if (!validate_src(cJSON_GetObjectItemCaseSensitive(b, "src"), berr, sizeof(berr))) {
            snprintf(err, errlen, "binding %d: %s", i, berr);
            return false;
        }
        const cJSON *act = cJSON_GetObjectItemCaseSensitive(b, "action");
        if (!action_validate(act, berr, sizeof(berr))) {
            snprintf(err, errlen, "binding %d: %s", i, berr);
            return false;
        }
        /* A page switch has no profile to switch within here. */
        const cJSON *kind = cJSON_GetObjectItemCaseSensitive(act, "kind");
        if (cJSON_IsString(kind) && !strcmp(kind->valuestring, "page")) {
            snprintf(err, errlen, "binding %d: page actions need a deck profile; "
                                  "use a profile action instead", i);
            return false;
        }
    }

    char *txt = cJSON_PrintUnformatted(doc);
    if (!txt) {
        snprintf(err, errlen, "out of memory");
        return false;
    }
    char path[64];
    path_for(addr->valuestring, path, sizeof(path));
    FILE *f = fopen(path, "w");
    bool ok = f && fwrite(txt, 1, strlen(txt), f) == strlen(txt);
    if (f) {
        fclose(f);
    }
    free(txt);
    if (!ok) {
        snprintf(err, errlen, "cannot write remap storage");
        return false;
    }
    /* Binding indices shifted, so the edge state is meaningless now. */
    remap_reset_device(addr->valuestring);
    ESP_LOGI(TAG, "stored %d binding(s) for %s", n, addr->valuestring);
    return true;
}

bool remap_del(const char *addr)
{
    if (!addr_ok(addr)) {
        return false;
    }
    char path[64];
    path_for(addr, path, sizeof(path));
    remap_reset_device(addr);
    return remove(path) == 0;
}

cJSON *remap_list(void)
{
    cJSON *arr = cJSON_CreateArray();
    DIR *d = opendir(DECK_FS_BASE);
    if (!d) {
        return arr;
    }
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "r_", 2) != 0) {
            continue;
        }
        char path[64];
        snprintf(path, sizeof(path), DECK_FS_BASE "/%.40s", e->d_name);
        char *txt = read_file(path);
        if (!txt) {
            continue;
        }
        cJSON *doc = cJSON_Parse(txt);
        free(txt);
        if (!doc) {
            continue;
        }
        const cJSON *addr = cJSON_GetObjectItemCaseSensitive(doc, "addr");
        if (cJSON_IsString(addr)) {
            cJSON *sum = cJSON_CreateObject();
            cJSON_AddStringToObject(sum, "addr", addr->valuestring);
            const cJSON *nm = cJSON_GetObjectItemCaseSensitive(doc, "name");
            cJSON_AddStringToObject(sum, "name", cJSON_IsString(nm) ? nm->valuestring : "");
            const cJSON *binds = cJSON_GetObjectItemCaseSensitive(doc, "bindings");
            cJSON_AddNumberToObject(sum, "bindings",
                                    cJSON_IsArray(binds) ? cJSON_GetArraySize(binds) : 0);
            cJSON_AddBoolToObject(sum, "passthrough",
                cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(doc, "passthrough")));
            cJSON_AddItemToArray(arr, sum);
        }
        cJSON_Delete(doc);
    }
    closedir(d);
    return arr;
}

/* ------------------------------------------------------------------ runtime */

void remap_init(void)
{
    s_lock = xSemaphoreCreateMutex();
}

static dev_state_t *state_for(const char *addr)
{
    for (int i = 0; i < REMAP_MAX_DEVICES; i++) {
        if (s_dev[i].used && !strcasecmp(s_dev[i].addr, addr)) {
            return &s_dev[i];
        }
    }
    for (int i = 0; i < REMAP_MAX_DEVICES; i++) {
        if (!s_dev[i].used) {
            memset(&s_dev[i], 0, sizeof(s_dev[i]));
            snprintf(s_dev[i].addr, sizeof(s_dev[i].addr), "%s", addr);
            s_dev[i].used = true;
            return &s_dev[i];
        }
    }
    return NULL;
}

void remap_reset_device(const char *addr)
{
    if (!s_lock || !addr) {
        return;
    }
    xSemaphoreTake(s_lock, portMAX_DELAY);
    for (int i = 0; i < REMAP_MAX_DEVICES; i++) {
        if (s_dev[i].used && !strcasecmp(s_dev[i].addr, addr)) {
            s_dev[i].used = false;
        }
    }
    xSemaphoreGive(s_lock);
}

void remap_set_learn(bool on, bool mute)
{
    s_learn = on;
    s_learn_mute = mute;
    ESP_LOGI(TAG, "learn mode %s%s", on ? "on" : "off",
             (on && mute) ? " (actions muted)" : "");
}

bool remap_learn_active(void)
{
    return s_learn;
}

/* Is a keyboard usage currently held, given a boot-protocol report?
 * Modifier bits are addressed as their canonical usages 0xE0..0xE7, so the app
 * can bind "left shift" the same way it binds any other key. */
static bool kb_usage_held(const uint8_t *data, uint16_t len, int usage)
{
    if (len < 3) {
        return false;
    }
    if (usage >= 0xE0 && usage <= 0xE7) {
        return (data[0] >> (usage - 0xE0)) & 1;
    }
    for (int i = 2; i < len && i < 8; i++) {
        if (data[i] == usage) {
            return true;
        }
    }
    return false;
}

static bool src_state(const cJSON *src, int usage_class,
                      const uint8_t *data, uint16_t len)
{
    const cJSON *kind = cJSON_GetObjectItemCaseSensitive(src, "kind");
    if (!cJSON_IsString(kind)) {
        return false;
    }
    const char *k = kind->valuestring;
    const cJSON *bytev = cJSON_GetObjectItemCaseSensitive(src, "byte");
    int byte = cJSON_IsNumber(bytev) ? bytev->valueint : -1;

    if (!strcmp(k, "btn")) {
        const cJSON *bitv = cJSON_GetObjectItemCaseSensitive(src, "bit");
        int bit = cJSON_IsNumber(bitv) ? bitv->valueint : -1;
        if (byte < 0 || byte >= len || bit < 0 || bit > 7) {
            return false;
        }
        return ((data[byte] >> bit) & 1) != 0;
    }
    if (!strcmp(k, "axis")) {
        const cJSON *thrv = cJSON_GetObjectItemCaseSensitive(src, "thr");
        const cJSON *dirv = cJSON_GetObjectItemCaseSensitive(src, "dir");
        if (byte < 0 || byte >= len || !cJSON_IsNumber(thrv) || !cJSON_IsString(dirv)) {
            return false;
        }
        int thr = thrv->valueint;
        return !strcmp(dirv->valuestring, "lo") ? (data[byte] < thr) : (data[byte] > thr);
    }
    if (!strcmp(k, "key")) {
        if (usage_class != ESP_HID_USAGE_KEYBOARD) {
            return false;
        }
        const cJSON *uv = cJSON_GetObjectItemCaseSensitive(src, "usage");
        return cJSON_IsNumber(uv) && kb_usage_held(data, len, uv->valueint);
    }
    return false;
}

/* Summarise what changed, so the editor can offer the predicate the user just
 * triggered rather than making them guess byte offsets. */
static void emit_learn_event(const char *addr, uint16_t report_id, int usage_class,
                             const uint8_t *data, uint16_t len,
                             const uint8_t *prev, uint16_t prev_len, bool have_prev)
{
    cJSON *ev = cJSON_CreateObject();
    cJSON_AddNumberToObject(ev, "id", 0);
    cJSON_AddStringToObject(ev, "ev", "src_input");
    cJSON_AddStringToObject(ev, "addr", addr);
    cJSON_AddNumberToObject(ev, "report", report_id);
    cJSON_AddNumberToObject(ev, "len", len);
    cJSON_AddNumberToObject(ev, "usage", usage_class);

    cJSON *bits = cJSON_AddArrayToObject(ev, "bits");
    cJSON *bytes = cJSON_AddArrayToObject(ev, "bytes");
    if (have_prev) {
        uint16_t n = len < prev_len ? len : prev_len;
        for (uint16_t i = 0; i < n; i++) {
            uint8_t diff = (uint8_t)(data[i] ^ prev[i]);
            if (!diff) {
                continue;
            }
            /* A byte that moved a lot is almost certainly an analog axis; one
             * that changed a single bit is almost certainly a button. Both are
             * reported and the editor decides which to offer first. */
            int delta = (int)data[i] - (int)prev[i];
            if (delta < 0) delta = -delta;
            cJSON *by = cJSON_CreateObject();
            cJSON_AddNumberToObject(by, "byte", i);
            cJSON_AddNumberToObject(by, "val", data[i]);
            cJSON_AddNumberToObject(by, "prev", prev[i]);
            cJSON_AddBoolToObject(by, "analog", delta > 8);
            cJSON_AddItemToArray(bytes, by);

            for (int b = 0; b < 8; b++) {
                if (diff & (1u << b)) {
                    cJSON *bit = cJSON_CreateObject();
                    cJSON_AddNumberToObject(bit, "byte", i);
                    cJSON_AddNumberToObject(bit, "bit", b);
                    cJSON_AddBoolToObject(bit, "down", ((data[i] >> b) & 1) != 0);
                    cJSON_AddItemToArray(bits, bit);
                }
            }
        }
    }

    if (usage_class == ESP_HID_USAGE_KEYBOARD && len >= 3) {
        cJSON *keys = cJSON_AddArrayToObject(ev, "keys");
        for (int u = 0xE0; u <= 0xE7; u++) {
            if (kb_usage_held(data, len, u)) {
                cJSON_AddItemToArray(keys, cJSON_CreateNumber(u));
            }
        }
        for (int i = 2; i < len && i < 8; i++) {
            if (data[i]) {
                cJSON_AddItemToArray(keys, cJSON_CreateNumber(data[i]));
            }
        }
    }

    /* The raw report, hex-encoded, as the last resort for an odd device. */
    char hex[REMAP_MAX_REPORT * 2 + 1];
    uint16_t hn = len < REMAP_MAX_REPORT ? len : REMAP_MAX_REPORT;
    for (uint16_t i = 0; i < hn; i++) {
        snprintf(hex + i * 2, 3, "%02x", data[i]);
    }
    hex[hn * 2] = 0;
    cJSON_AddStringToObject(ev, "raw", hex);

    net_link_send_json(ev);
}

/* Forward the keys that nothing has claimed, so a remapped keyboard still types
 * normally everywhere the user did not rebind. */
static void passthrough_keys(dev_state_t *st, const cJSON *binds,
                             const uint8_t *data, uint16_t len)
{
    uint8_t want[8] = {0};
    int wn = 0;
    uint8_t want_mods = 0;

    int nb = cJSON_IsArray(binds) ? cJSON_GetArraySize(binds) : 0;

    for (int u = 0xE0; u <= 0xE7; u++) {
        if (!kb_usage_held(data, len, u)) {
            continue;
        }
        bool bound = false;
        for (int i = 0; i < nb; i++) {
            const cJSON *src = cJSON_GetObjectItemCaseSensitive(
                cJSON_GetArrayItem(binds, i), "src");
            const cJSON *uv = cJSON_GetObjectItemCaseSensitive(src, "usage");
            const cJSON *kv = cJSON_GetObjectItemCaseSensitive(src, "kind");
            if (cJSON_IsString(kv) && !strcmp(kv->valuestring, "key") &&
                cJSON_IsNumber(uv) && uv->valueint == u) {
                bound = true;
                break;
            }
        }
        if (!bound) {
            want_mods |= (uint8_t)(1u << (u - 0xE0));
        }
    }

    for (int i = 2; i < len && i < 8; i++) {
        uint8_t u = data[i];
        if (!u) {
            continue;
        }
        bool bound = false;
        for (int j = 0; j < nb; j++) {
            const cJSON *src = cJSON_GetObjectItemCaseSensitive(
                cJSON_GetArrayItem(binds, j), "src");
            const cJSON *uv = cJSON_GetObjectItemCaseSensitive(src, "usage");
            const cJSON *kv = cJSON_GetObjectItemCaseSensitive(src, "kind");
            if (cJSON_IsString(kv) && !strcmp(kv->valuestring, "key") &&
                cJSON_IsNumber(uv) && uv->valueint == u) {
                bound = true;
                break;
            }
        }
        if (!bound && wn < 8) {
            want[wn++] = u;
        }
    }

    /* Release what is no longer held. */
    for (int i = 0; i < 8; i++) {
        uint8_t u = st->passed[i];
        if (!u) {
            continue;
        }
        bool still = false;
        for (int j = 0; j < wn; j++) {
            if (want[j] == u) { still = true; break; }
        }
        if (!still) {
            usb_hid_key_up(0, u);
        }
    }
    /* Press what is newly held. */
    for (int j = 0; j < wn; j++) {
        bool was = false;
        for (int i = 0; i < 8; i++) {
            if (st->passed[i] == want[j]) { was = true; break; }
        }
        if (!was) {
            usb_hid_key_down(0, want[j]);
        }
    }
    /* Modifiers as a set difference. */
    uint8_t press = (uint8_t)(want_mods & ~st->passed_mods);
    uint8_t release = (uint8_t)(st->passed_mods & ~want_mods);
    if (press) {
        usb_hid_key_down(press, 0);
    }
    if (release) {
        usb_hid_key_up(release, 0);
    }

    memset(st->passed, 0, sizeof(st->passed));
    for (int j = 0; j < wn; j++) {
        st->passed[j] = want[j];
    }
    st->passed_mods = want_mods;
}

/* A BLE mouse is forwarded verbatim: there is nothing useful to remap on it and
 * a paired mouse that simply works is the expected behaviour. */
static void passthrough_mouse(const uint8_t *data, uint16_t len)
{
    if (len < 3) {
        return;
    }
    int8_t dx = (int8_t)data[1];
    int8_t dy = (int8_t)data[2];
    int8_t wheel = len > 3 ? (int8_t)data[3] : 0;
    uint8_t mask = (uint8_t)(data[0] & 0x1f);
    uint8_t now = usb_hid_mouse_button_state();
    uint8_t press = (uint8_t)(mask & ~now);
    uint8_t release = (uint8_t)(now & ~mask);
    if (press) {
        usb_hid_mouse_buttons(press, true);
    }
    if (release) {
        usb_hid_mouse_buttons(release, false);
    }
    if (dx || dy || wheel) {
        usb_hid_mouse_rel(dx, dy, wheel, 0);
    }
}

void remap_feed(const char *addr, int usage_class, uint16_t report_id,
                const uint8_t *data, uint16_t len)
{
    if (!s_lock || !addr || !data || len == 0) {
        return;
    }
    if (len > REMAP_MAX_REPORT) {
        len = REMAP_MAX_REPORT;
    }

    cJSON *doc = remap_get(addr);
    const cJSON *binds = doc ? cJSON_GetObjectItemCaseSensitive(doc, "bindings") : NULL;
    bool passthrough = doc && cJSON_IsTrue(
        cJSON_GetObjectItemCaseSensitive(doc, "passthrough"));

    xSemaphoreTake(s_lock, portMAX_DELAY);
    dev_state_t *st = state_for(addr);
    if (!st) {
        xSemaphoreGive(s_lock);
        cJSON_Delete(doc);
        return;
    }

    if (s_learn) {
        emit_learn_event(addr, report_id, usage_class, data, len,
                         st->prev, st->prev_len, st->have_prev);
    }

    bool fire_actions = !(s_learn && s_learn_mute);

    int nb = cJSON_IsArray(binds) ? cJSON_GetArraySize(binds) : 0;
    if (nb > REMAP_MAX_BINDINGS) {
        nb = REMAP_MAX_BINDINGS;
    }
    for (int i = 0; i < nb; i++) {
        const cJSON *b = cJSON_GetArrayItem(binds, i);
        const cJSON *src = cJSON_GetObjectItemCaseSensitive(b, "src");
        bool now = src_state(src, usage_class, data, len);
        bool was = st->state[i];
        if (now == was) {
            continue;
        }
        st->state[i] = now;
        if (!fire_actions) {
            continue;
        }
        const cJSON *act = cJSON_GetObjectItemCaseSensitive(b, "action");
        /* The physical input has a real press and release, so bindings get a
         * genuine edge pair rather than a synthetic tap: a `hold` binding holds
         * for exactly as long as the button is down. */
        char err[160] = {0};
        if (!action_fire(act, now, !now, NULL, err, sizeof(err))) {
            ESP_LOGW(TAG, "binding %d failed: %s", i, err);
        }
    }

    if (passthrough && fire_actions) {
        if (usage_class == ESP_HID_USAGE_KEYBOARD) {
            passthrough_keys(st, binds, data, len);
        } else if (usage_class == ESP_HID_USAGE_MOUSE) {
            passthrough_mouse(data, len);
        }
    }

    memcpy(st->prev, data, len);
    st->prev_len = len;
    st->have_prev = true;
    xSemaphoreGive(s_lock);

    cJSON_Delete(doc);
}
