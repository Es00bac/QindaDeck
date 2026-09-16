#include "protocol.h"

#include <inttypes.h>
#include <stdlib.h>
#include <string.h>

#include "bt_host.h"
#include "deck_config.h"
#include "displays.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "hid_keymap.h"
#include "macro.h"
#include "net_link.h"
#include "profiles.h"
#include "remap.h"
#include "sdcard.h"
#include "sd_ops.h"
#include "usb_hid.h"

static const char *TAG = "proto";

/* ------------------------------------------------------------------ helpers */

static cJSON *reply_for(const cJSON *req, bool ok)
{
    cJSON *r = cJSON_CreateObject();
    const cJSON *id = cJSON_GetObjectItemCaseSensitive(req, "id");
    cJSON_AddNumberToObject(r, "id", cJSON_IsNumber(id) ? id->valueint : 0);
    cJSON_AddBoolToObject(r, "ok", ok);
    return r;
}

static void fail(const cJSON *req, const char *msg)
{
    cJSON *r = reply_for(req, false);
    cJSON_AddStringToObject(r, "err", msg);
    net_link_send_json(r);
}

static void add_identity(cJSON *o)
{
    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    char macs[18];
    snprintf(macs, sizeof(macs), "%02x:%02x:%02x:%02x:%02x:%02x",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);

    char name[40];
    net_link_get_name(name, sizeof(name));
    char ip[16];
    net_link_ip(ip, sizeof(ip));
    char slug[40];
    int page = 0;
    profiles_get_active(slug, sizeof(slug), &page);

    cJSON_AddStringToObject(o, "fw", DECK_FW_VERSION);
    cJSON_AddStringToObject(o, "chip", "esp32s3");
    cJSON_AddStringToObject(o, "mac", macs);
    cJSON_AddStringToObject(o, "name", name);
    cJSON_AddStringToObject(o, "ip", ip);
    cJSON_AddBoolToObject(o, "usb", usb_hid_mounted());
    cJSON_AddBoolToObject(o, "sd", sdcard_present());
    cJSON_AddStringToObject(o, "sd_owner",
                            !sdcard_present() ? "none"
                            : sdcard_owner_app() ? "app" : "usb");
    cJSON_AddNumberToObject(o, "sd_mb", (double)(sdcard_capacity_bytes() >> 20));
    cJSON_AddBoolToObject(o, "ap", net_link_ap_mode());
    cJSON_AddNumberToObject(o, "heap", (double)esp_get_free_heap_size());
    cJSON_AddNumberToObject(o, "uptime", (double)(esp_timer_get_time() / 1000000));
    cJSON_AddStringToObject(o, "active", slug);
    cJSON_AddNumberToObject(o, "page", page);
    cJSON_AddNumberToObject(o, "profiles", profiles_count());
    cJSON_AddNumberToObject(o, "tick", DECK_TICK_MS);
    cJSON_AddNumberToObject(o, "abs_max", DECK_ABS_MAX);
    /* BLE only on this chip; the app uses this to explain why a Classic-only
     * controller never shows up in a scan. */
    cJSON_AddStringToObject(o, "bt", "ble");
    cJSON_AddItemToObject(o, "bt_devices", bt_host_list());
    cJSON_AddBoolToObject(o, "learn", remap_learn_active());
    const char *running = macro_running();
    if (running) {
        cJSON_AddStringToObject(o, "running", running);
    } else {
        cJSON_AddNullToObject(o, "running");
    }
}

/* Reboots are deferred so the reply actually reaches the phone first. */
static void reboot_task(void *arg)
{
    (void)arg;
    vTaskDelay(pdMS_TO_TICKS(400));
    esp_restart();
}

static void schedule_reboot(void)
{
    xTaskCreate(reboot_task, "deck_reboot", 2048, NULL, 4, NULL);
}

static void on_macro_event(const char *state, const char *name, const char *reason)
{
    cJSON *ev = cJSON_CreateObject();
    cJSON_AddNumberToObject(ev, "id", 0);
    cJSON_AddStringToObject(ev, "ev", "macro");
    cJSON_AddStringToObject(ev, "state", state);
    cJSON_AddStringToObject(ev, "name", name);
    if (reason) {
        cJSON_AddStringToObject(ev, "reason", reason);
    }
    net_link_send_json(ev);
}

void protocol_init(void)
{
    macro_set_event_cb(on_macro_event);
}

void protocol_on_connect(void)
{
    cJSON *ev = cJSON_CreateObject();
    cJSON_AddNumberToObject(ev, "id", 0);
    cJSON_AddStringToObject(ev, "ev", "hello");
    add_identity(ev);
    net_link_send_json(ev);
}

/* ------------------------------------------------------------------ JSON ops */

static void handle_json(const uint8_t *payload, size_t len)
{
    cJSON *req = cJSON_ParseWithLength((const char *)payload, len);
    if (!req) {
        ESP_LOGW(TAG, "unparseable control frame");
        return;
    }
    const cJSON *opv = cJSON_GetObjectItemCaseSensitive(req, "op");
    if (!cJSON_IsString(opv)) {
        fail(req, "missing op");
        cJSON_Delete(req);
        return;
    }
    const char *op = opv->valuestring;

    if (!strcmp(op, "hello") || !strcmp(op, "get_status")) {
        cJSON *r = reply_for(req, true);
        add_identity(r);
        net_link_send_json(r);

    } else if (!strcmp(op, "list_profiles")) {
        cJSON *r = reply_for(req, true);
        cJSON_AddItemToObject(r, "profiles", profiles_list());
        net_link_send_json(r);

    } else if (!strcmp(op, "get_profile")) {
        const cJSON *slug = cJSON_GetObjectItemCaseSensitive(req, "slug");
        cJSON *p = cJSON_IsString(slug) ? profiles_get(slug->valuestring) : NULL;
        if (!p) {
            fail(req, "no such profile");
        } else {
            cJSON *r = reply_for(req, true);
            cJSON_AddItemToObject(r, "profile", p);
            net_link_send_json(r);
        }

    } else if (!strcmp(op, "put_profile")) {
        const cJSON *p = cJSON_GetObjectItemCaseSensitive(req, "profile");
        char err[220] = {0};
        if (!p || !profiles_put(p, err, sizeof(err))) {
            fail(req, err[0] ? err : "invalid profile");
        } else {
            net_link_send_json(reply_for(req, true));
        }

    } else if (!strcmp(op, "del_profile")) {
        const cJSON *slug = cJSON_GetObjectItemCaseSensitive(req, "slug");
        if (!cJSON_IsString(slug) || !profiles_del(slug->valuestring)) {
            fail(req, "cannot delete that profile");
        } else {
            net_link_send_json(reply_for(req, true));
        }

    } else if (!strcmp(op, "set_active")) {
        const cJSON *slug = cJSON_GetObjectItemCaseSensitive(req, "slug");
        const cJSON *page = cJSON_GetObjectItemCaseSensitive(req, "page");
        if (!cJSON_IsString(slug) ||
            !profiles_set_active(slug->valuestring,
                                 cJSON_IsNumber(page) ? page->valueint : 0)) {
            fail(req, "cannot activate that profile");
        } else {
            cJSON *r = reply_for(req, true);
            add_identity(r);
            net_link_send_json(r);
        }

    } else if (!strcmp(op, "press")) {
        const cJSON *slug = cJSON_GetObjectItemCaseSensitive(req, "slug");
        const cJSON *page = cJSON_GetObjectItemCaseSensitive(req, "page");
        const cJSON *index = cJSON_GetObjectItemCaseSensitive(req, "index");
        const cJSON *state = cJSON_GetObjectItemCaseSensitive(req, "state");
        if (!cJSON_IsNumber(index)) {
            fail(req, "press needs an index");
        } else {
            char err[220] = {0};
            bool ok = profiles_press(cJSON_IsString(slug) ? slug->valuestring : NULL,
                                     cJSON_IsNumber(page) ? page->valueint : -1,
                                     index->valueint,
                                     cJSON_IsString(state) ? state->valuestring : "tap",
                                     err, sizeof(err));
            if (!ok) {
                fail(req, err[0] ? err : "press failed");
            } else {
                net_link_send_json(reply_for(req, true));
            }
        }

    } else if (!strcmp(op, "run_macro")) {
        const cJSON *m = cJSON_GetObjectItemCaseSensitive(req, "macro");
        char err[220] = {0};
        deck_macro_t *compiled = macro_compile(m, err, sizeof(err));
        if (!compiled) {
            fail(req, err[0] ? err : "invalid macro");
        } else if (!macro_submit(compiled)) {
            macro_free(compiled);
            fail(req, "macro engine unavailable");
        } else {
            cJSON *r = reply_for(req, true);
            cJSON_AddNumberToObject(r, "steps", macro_step_count(compiled));
            net_link_send_json(r);
        }

    } else if (!strcmp(op, "stop_macro")) {
        macro_stop();
        usb_hid_release_everything();
        net_link_send_json(reply_for(req, true));

    } else if (!strcmp(op, "get_displays")) {
        cJSON *r = reply_for(req, true);
        cJSON_AddItemToObject(r, "displays", displays_get_json());
        net_link_send_json(r);

    } else if (!strcmp(op, "put_displays")) {
        const cJSON *d = cJSON_GetObjectItemCaseSensitive(req, "displays");
        char err[160] = {0};
        if (!d || !displays_set_json(d, err, sizeof(err))) {
            fail(req, err[0] ? err : "invalid display config");
        } else {
            net_link_send_json(reply_for(req, true));
        }

    } else if (!strcmp(op, "set_name")) {
        const cJSON *n = cJSON_GetObjectItemCaseSensitive(req, "name");
        if (!cJSON_IsString(n) || !net_link_set_name(n->valuestring)) {
            fail(req, "bad name");
        } else {
            net_link_send_json(reply_for(req, true));
        }

    } else if (!strcmp(op, "wifi_scan")) {
        cJSON *r = reply_for(req, true);
        cJSON_AddItemToObject(r, "nets", net_link_scan());
        net_link_send_json(r);

    } else if (!strcmp(op, "wifi_set")) {
        const cJSON *ssid = cJSON_GetObjectItemCaseSensitive(req, "ssid");
        const cJSON *pass = cJSON_GetObjectItemCaseSensitive(req, "pass");
        if (!cJSON_IsString(ssid) ||
            !net_link_wifi_set(ssid->valuestring,
                               cJSON_IsString(pass) ? pass->valuestring : "")) {
            fail(req, "bad credentials");
        } else {
            net_link_send_json(reply_for(req, true));
            schedule_reboot();
        }

    } else if (!strcmp(op, "wifi_forget")) {
        net_link_wifi_forget();
        net_link_send_json(reply_for(req, true));
        schedule_reboot();

    } else if (!strcmp(op, "reboot")) {
        net_link_send_json(reply_for(req, true));
        schedule_reboot();

    } else if (!strcmp(op, "factory_reset")) {
        profiles_factory_reset();
        net_link_send_json(reply_for(req, true));
        schedule_reboot();

    } else if (!strcmp(op, "bt_scan")) {
        if (bt_host_scanning()) {
            fail(req, "a scan is already running");
        } else {
            const cJSON *secs = cJSON_GetObjectItemCaseSensitive(req, "seconds");
            cJSON *r = reply_for(req, true);
            cJSON_AddItemToObject(r, "devices",
                                  bt_host_scan(cJSON_IsNumber(secs) ? secs->valueint : 5));
            net_link_send_json(r);
        }

    } else if (!strcmp(op, "bt_pair")) {
        const cJSON *addr = cJSON_GetObjectItemCaseSensitive(req, "addr");
        const cJSON *at = cJSON_GetObjectItemCaseSensitive(req, "addr_type");
        const cJSON *nm = cJSON_GetObjectItemCaseSensitive(req, "name");
        char err[180] = {0};
        if (!cJSON_IsString(addr) ||
            !bt_host_pair(addr->valuestring,
                          cJSON_IsNumber(at) ? at->valueint : -1,
                          cJSON_IsString(nm) ? nm->valuestring : NULL,
                          err, sizeof(err))) {
            fail(req, err[0] ? err : "cannot pair that device");
        } else {
            /* Connecting is asynchronous; the outcome arrives as a `bt` event. */
            cJSON *r = reply_for(req, true);
            cJSON_AddStringToObject(r, "pending", "connecting");
            net_link_send_json(r);
        }

    } else if (!strcmp(op, "bt_forget")) {
        const cJSON *addr = cJSON_GetObjectItemCaseSensitive(req, "addr");
        if (!cJSON_IsString(addr) || !bt_host_forget(addr->valuestring)) {
            fail(req, "not a remembered device");
        } else {
            net_link_send_json(reply_for(req, true));
        }

    } else if (!strcmp(op, "bt_list")) {
        cJSON *r = reply_for(req, true);
        cJSON_AddItemToObject(r, "devices", bt_host_list());
        net_link_send_json(r);

    } else if (!strcmp(op, "list_remaps")) {
        cJSON *r = reply_for(req, true);
        cJSON_AddItemToObject(r, "remaps", remap_list());
        net_link_send_json(r);

    } else if (!strcmp(op, "get_remap")) {
        const cJSON *addr = cJSON_GetObjectItemCaseSensitive(req, "addr");
        cJSON *doc = cJSON_IsString(addr) ? remap_get(addr->valuestring) : NULL;
        cJSON *r = reply_for(req, true);
        if (doc) {
            cJSON_AddItemToObject(r, "remap", doc);
        } else {
            /* Not an error: a freshly paired device simply has no bindings yet. */
            cJSON_AddNullToObject(r, "remap");
        }
        net_link_send_json(r);

    } else if (!strcmp(op, "put_remap")) {
        const cJSON *doc = cJSON_GetObjectItemCaseSensitive(req, "remap");
        char err[220] = {0};
        if (!doc || !remap_put(doc, err, sizeof(err))) {
            fail(req, err[0] ? err : "invalid remap");
        } else {
            net_link_send_json(reply_for(req, true));
        }

    } else if (!strcmp(op, "del_remap")) {
        const cJSON *addr = cJSON_GetObjectItemCaseSensitive(req, "addr");
        if (!cJSON_IsString(addr) || !remap_del(addr->valuestring)) {
            fail(req, "no remap for that device");
        } else {
            net_link_send_json(reply_for(req, true));
        }

    } else if (!strcmp(op, "learn")) {
        const cJSON *on = cJSON_GetObjectItemCaseSensitive(req, "on");
        const cJSON *mute = cJSON_GetObjectItemCaseSensitive(req, "mute");
        /* Muting defaults to on: while the editor is capturing a button, firing
         * that button's existing action would be actively unhelpful. */
        remap_set_learn(cJSON_IsTrue(on),
                        cJSON_IsBool(mute) ? cJSON_IsTrue(mute) : true);
        cJSON *r = reply_for(req, true);
        cJSON_AddBoolToObject(r, "learn", remap_learn_active());
        net_link_send_json(r);

    } else if (sd_ops_handle(op, req)) {
        /* handled, reply already sent */
    } else {
        fail(req, "unknown op");
    }

    cJSON_Delete(req);
}

/* ------------------------------------------------------------------ binary */

enum {
    IN_MOUSE_REL = 0x10,
    IN_MOUSE_ABS = 0x11,
    IN_KEY       = 0x12,
    IN_CONSUMER  = 0x13,
};

static int16_t be16(const uint8_t *p)
{
    return (int16_t)(((uint16_t)p[0] << 8) | p[1]);
}

static void apply_buttons(uint8_t mask)
{
    uint8_t now = usb_hid_mouse_button_state();
    uint8_t press = (uint8_t)(mask & ~now);
    uint8_t release = (uint8_t)(now & ~mask);
    if (press) {
        usb_hid_mouse_buttons(press, true);
    }
    if (release) {
        usb_hid_mouse_buttons(release, false);
    }
}

static void handle_input(const uint8_t *p, size_t len)
{
    if (len < 1) {
        return;
    }
    switch (p[0]) {
    case IN_MOUSE_REL:
        /* kind, dx:i16, dy:i16, wheel:i8, hwheel:i8, buttons:u8 */
        if (len >= 8) {
            apply_buttons(p[7]);
            usb_hid_mouse_rel(be16(p + 1), be16(p + 3), (int8_t)p[5], (int8_t)p[6]);
        }
        break;
    case IN_MOUSE_ABS:
        /* kind, x:u16, y:u16, wheel:i8, buttons:u8 */
        if (len >= 7) {
            apply_buttons(p[6]);
            usb_hid_mouse_abs((uint16_t)be16(p + 1), (uint16_t)be16(p + 3), (int8_t)p[5]);
        }
        break;
    case IN_KEY:
        /* kind, down:u8, mods:u8, usage:u8 */
        if (len >= 4) {
            if (p[1]) {
                usb_hid_key_down(p[2], p[3]);
            } else {
                usb_hid_key_up(p[2], p[3]);
            }
        }
        break;
    case IN_CONSUMER:
        /* kind, usage:u16, down:u8 */
        if (len >= 4) {
            usb_hid_consumer((uint16_t)be16(p + 1), p[3] != 0);
        }
        break;
    default:
        break;
    }
}

void protocol_handle_frame(uint8_t type, const uint8_t *payload, size_t len)
{
    switch (type) {
    case DECK_FRAME_JSON:
        handle_json(payload, len);
        break;
    case DECK_FRAME_INPUT:
        handle_input(payload, len);
        break;
    default:
        ESP_LOGW(TAG, "unknown frame type 0x%02x", type);
        break;
    }
}
