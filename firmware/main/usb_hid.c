#include "usb_hid.h"

#include <string.h>

#include "class/hid/hid_device.h"
#include "deck_config.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "tinyusb.h"
#include "tinyusb_default_config.h"

static const char *TAG = "usb_hid";

/* ------------------------------------------------------------------ descriptors */

/* An absolute pointer is not one of TinyUSB's canned descriptors, so it is
 * spelled out here. Report layout for ID 4 is:
 *   [buttons u8][x u16 LE][y u16 LE][wheel i8]  = 6 bytes
 * Declaring it under Generic Desktop / Mouse / Pointer with absolute axes is
 * the same shape KVM-over-IP dongles use, which is why hosts accept it without
 * treating the device as a graphics tablet. */
#define DECK_REPORT_DESC_ABSPTR(...)                                            \
    HID_USAGE_PAGE(HID_USAGE_PAGE_DESKTOP),                                     \
    HID_USAGE(HID_USAGE_DESKTOP_MOUSE),                                         \
    HID_COLLECTION(HID_COLLECTION_APPLICATION),                                 \
        __VA_ARGS__                                                             \
        HID_USAGE(HID_USAGE_DESKTOP_POINTER),                                   \
        HID_COLLECTION(HID_COLLECTION_PHYSICAL),                                \
            /* five buttons, then three bits of padding */                      \
            HID_USAGE_PAGE(HID_USAGE_PAGE_BUTTON),                              \
            HID_USAGE_MIN(1), HID_USAGE_MAX(5),                                 \
            HID_LOGICAL_MIN(0), HID_LOGICAL_MAX(1),                             \
            HID_REPORT_COUNT(5), HID_REPORT_SIZE(1),                            \
            HID_INPUT(HID_DATA | HID_VARIABLE | HID_ABSOLUTE),                  \
            HID_REPORT_COUNT(1), HID_REPORT_SIZE(3),                            \
            HID_INPUT(HID_CONSTANT),                                            \
            /* 16-bit absolute X and Y over 0..32767 */                         \
            HID_USAGE_PAGE(HID_USAGE_PAGE_DESKTOP),                             \
            HID_USAGE(HID_USAGE_DESKTOP_X),                                     \
            HID_USAGE(HID_USAGE_DESKTOP_Y),                                     \
            HID_LOGICAL_MIN_N(0, 2), HID_LOGICAL_MAX_N(DECK_ABS_MAX, 2),         \
            HID_PHYSICAL_MIN_N(0, 2), HID_PHYSICAL_MAX_N(DECK_ABS_MAX, 2),       \
            HID_REPORT_SIZE(16), HID_REPORT_COUNT(2),                           \
            HID_INPUT(HID_DATA | HID_VARIABLE | HID_ABSOLUTE),                  \
            /* wheel stays relative even on an absolute pointer */              \
            HID_USAGE(HID_USAGE_DESKTOP_WHEEL),                                 \
            HID_LOGICAL_MIN(0x81), HID_LOGICAL_MAX(0x7f),                       \
            HID_PHYSICAL_MIN(0), HID_PHYSICAL_MAX(0),                           \
            HID_REPORT_SIZE(8), HID_REPORT_COUNT(1),                            \
            HID_INPUT(HID_DATA | HID_VARIABLE | HID_RELATIVE),                  \
        HID_COLLECTION_END,                                                     \
    HID_COLLECTION_END

static const uint8_t s_report_desc[] = {
    TUD_HID_REPORT_DESC_KEYBOARD(HID_REPORT_ID(DECK_RPT_KEYBOARD)),
    TUD_HID_REPORT_DESC_MOUSE(HID_REPORT_ID(DECK_RPT_MOUSE)),
    TUD_HID_REPORT_DESC_CONSUMER(HID_REPORT_ID(DECK_RPT_CONSUMER)),
    DECK_REPORT_DESC_ABSPTR(HID_REPORT_ID(DECK_RPT_ABSPTR)),
};

#define DECK_CFG_TOTAL_LEN (TUD_CONFIG_DESC_LEN + TUD_HID_DESC_LEN)

/* bInterval 1 => a 1 ms polling interval at full speed. The macro engine ticks
 * every 8 ms, so the endpoint is never the bottleneck for smooth motion. */
static const uint8_t s_cfg_desc[] = {
    TUD_CONFIG_DESCRIPTOR(1, 1, 0, DECK_CFG_TOTAL_LEN,
                          TUSB_DESC_CONFIG_ATT_REMOTE_WAKEUP, 100),
    TUD_HID_DESCRIPTOR(0, 4, false, sizeof(s_report_desc), 0x81, 16, 1),
};

static char s_serial[13];
static const char *s_strings[5] = {
    (char[]){0x09, 0x04},  /* English (0x0409) */
    "Qinda",
    "QindaDeck",
    s_serial,
    "QindaDeck HID",
};

/* ------------------------------------------------------------------ state */

static SemaphoreHandle_t s_lock;

static struct {
    uint8_t mods;
    uint8_t keys[6];
    uint8_t buttons;
    uint16_t abs_x, abs_y;
    bool have_abs;
    bool last_was_abs;
    uint16_t consumer;
} s_state;

/* TinyUSB refuses a report while the previous one is still in flight. At a 1 ms
 * interval a short spin is enough; giving up is better than blocking the macro
 * engine past its tick. */
static bool wait_ready(void)
{
    for (int i = 0; i < 20; i++) {
        if (tud_hid_n_ready(0)) {
            return true;
        }
        vTaskDelay(pdMS_TO_TICKS(1));
    }
    return false;
}

static void send_keyboard_locked(void)
{
    if (!tud_mounted() || !wait_ready()) {
        return;
    }
    tud_hid_keyboard_report(DECK_RPT_KEYBOARD, s_state.mods, s_state.keys);
}

/* A detach invalidates the host's idea of where the pointer is, so the next
 * absolute move should land rather than interpolate from a stale origin. */
void usb_hid_forget_abs(void)
{
    if (!s_lock) {
        return;
    }
    xSemaphoreTake(s_lock, portMAX_DELAY);
    s_state.have_abs = false;
    xSemaphoreGive(s_lock);
}

/* Intent is recorded whether or not the report can be delivered right now.
 * Two reasons: a macro must take the same wall-clock time with the cable out as
 * with it in, so timings can be authored and tested independently of the host;
 * and the pointer's absolute position is what we *asked* for, which is exactly
 * what the next interpolated move needs to start from. */
static void send_rel_locked(int dx, int dy, int wheel, int hwheel)
{
    if (dx < -127) dx = -127; else if (dx > 127) dx = 127;
    if (dy < -127) dy = -127; else if (dy > 127) dy = 127;
    if (wheel < -127) wheel = -127; else if (wheel > 127) wheel = 127;
    if (hwheel < -127) hwheel = -127; else if (hwheel > 127) hwheel = 127;
    s_state.last_was_abs = false;
    if (!tud_mounted() || !wait_ready()) {
        return;
    }
    tud_hid_mouse_report(DECK_RPT_MOUSE, s_state.buttons,
                         (int8_t)dx, (int8_t)dy, (int8_t)wheel, (int8_t)hwheel);
}

static void send_abs_locked(uint16_t x, uint16_t y, int wheel)
{
    if (wheel < -127) wheel = -127; else if (wheel > 127) wheel = 127;
    s_state.abs_x = x;
    s_state.abs_y = y;
    s_state.have_abs = true;
    s_state.last_was_abs = true;
    if (!tud_mounted() || !wait_ready()) {
        return;
    }
    uint8_t rpt[6];
    rpt[0] = s_state.buttons;
    rpt[1] = (uint8_t)(x & 0xff);
    rpt[2] = (uint8_t)(x >> 8);
    rpt[3] = (uint8_t)(y & 0xff);
    rpt[4] = (uint8_t)(y >> 8);
    rpt[5] = (uint8_t)(int8_t)wheel;
    tud_hid_n_report(0, DECK_RPT_ABSPTR, rpt, sizeof(rpt));
}

/* ------------------------------------------------------------------ API */

void usb_hid_init(void)
{
    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    snprintf(s_serial, sizeof(s_serial), "%02X%02X%02X%02X%02X%02X",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);

    s_lock = xSemaphoreCreateMutex();

    tinyusb_config_t cfg = TINYUSB_DEFAULT_CONFIG();
    cfg.descriptor.device = NULL;
    cfg.descriptor.full_speed_config = s_cfg_desc;
    cfg.descriptor.string = s_strings;
    cfg.descriptor.string_count = sizeof(s_strings) / sizeof(s_strings[0]);
#if (TUD_OPT_HIGH_SPEED)
    cfg.descriptor.high_speed_config = s_cfg_desc;
#endif
    ESP_ERROR_CHECK(tinyusb_driver_install(&cfg));
    ESP_LOGI(TAG, "composite HID up, serial %s", s_serial);
}

bool usb_hid_mounted(void)
{
    return tud_mounted();
}

void usb_hid_key_down(uint8_t mods, uint8_t usage)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    s_state.mods |= mods;
    if (usage) {
        bool present = false, placed = false;
        for (int i = 0; i < 6; i++) {
            if (s_state.keys[i] == usage) { present = true; break; }
        }
        if (!present) {
            for (int i = 0; i < 6; i++) {
                if (s_state.keys[i] == 0) { s_state.keys[i] = usage; placed = true; break; }
            }
            /* Six-key rollover is full: drop the oldest so the newest key still
             * registers, which is what a real keyboard controller does. */
            if (!placed) {
                memmove(s_state.keys, s_state.keys + 1, 5);
                s_state.keys[5] = usage;
            }
        }
    }
    send_keyboard_locked();
    xSemaphoreGive(s_lock);
}

void usb_hid_key_up(uint8_t mods, uint8_t usage)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    s_state.mods &= (uint8_t)~mods;
    if (usage) {
        for (int i = 0; i < 6; i++) {
            if (s_state.keys[i] == usage) { s_state.keys[i] = 0; }
        }
        /* Compact so the report stays a contiguous key list. */
        uint8_t packed[6] = {0};
        int n = 0;
        for (int i = 0; i < 6; i++) {
            if (s_state.keys[i]) { packed[n++] = s_state.keys[i]; }
        }
        memcpy(s_state.keys, packed, 6);
    }
    send_keyboard_locked();
    xSemaphoreGive(s_lock);
}

void usb_hid_keyboard_release_all(void)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    s_state.mods = 0;
    memset(s_state.keys, 0, sizeof(s_state.keys));
    send_keyboard_locked();
    xSemaphoreGive(s_lock);
}

void usb_hid_mouse_rel(int dx, int dy, int wheel, int hwheel)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    send_rel_locked(dx, dy, wheel, hwheel);
    xSemaphoreGive(s_lock);
}

void usb_hid_mouse_abs(uint16_t x, uint16_t y, int wheel)
{
    if (x > DECK_ABS_MAX) x = DECK_ABS_MAX;
    if (y > DECK_ABS_MAX) y = DECK_ABS_MAX;
    xSemaphoreTake(s_lock, portMAX_DELAY);
    send_abs_locked(x, y, wheel);
    xSemaphoreGive(s_lock);
}

void usb_hid_mouse_buttons(uint8_t mask, bool down)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    if (down) {
        s_state.buttons |= mask;
    } else {
        s_state.buttons &= (uint8_t)~mask;
    }
    /* Re-state the button mask on whichever pointer we last drove, so a press
     * issued mid-drag lands in the same coordinate space as the motion. */
    if (s_state.last_was_abs && s_state.have_abs) {
        send_abs_locked(s_state.abs_x, s_state.abs_y, 0);
    } else {
        send_rel_locked(0, 0, 0, 0);
    }
    xSemaphoreGive(s_lock);
}

uint8_t usb_hid_mouse_button_state(void)
{
    return s_state.buttons;
}

void usb_hid_consumer(uint16_t usage, bool down)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    uint16_t v = down ? usage : 0;
    s_state.consumer = v;
    if (tud_mounted() && wait_ready()) {
        tud_hid_n_report(0, DECK_RPT_CONSUMER, &v, sizeof(v));
    }
    xSemaphoreGive(s_lock);
}

void usb_hid_release_everything(void)
{
    usb_hid_keyboard_release_all();
    usb_hid_consumer(0, false);
    xSemaphoreTake(s_lock, portMAX_DELAY);
    if (s_state.buttons) {
        s_state.buttons = 0;
        if (s_state.last_was_abs && s_state.have_abs) {
            send_abs_locked(s_state.abs_x, s_state.abs_y, 0);
        } else {
            send_rel_locked(0, 0, 0, 0);
        }
    }
    xSemaphoreGive(s_lock);
}

void usb_hid_last_abs(uint16_t *x, uint16_t *y)
{
    if (x) *x = s_state.abs_x;
    if (y) *y = s_state.abs_y;
}

bool usb_hid_have_abs(void)
{
    return s_state.have_abs;
}

/* ------------------------------------------------------------------ callbacks */

uint8_t const *tud_hid_descriptor_report_cb(uint8_t instance)
{
    (void)instance;
    return s_report_desc;
}

uint16_t tud_hid_get_report_cb(uint8_t instance, uint8_t report_id,
                               hid_report_type_t report_type,
                               uint8_t *buffer, uint16_t reqlen)
{
    (void)instance; (void)report_id; (void)report_type; (void)buffer; (void)reqlen;
    return 0;
}

/* The host sends keyboard LED state here (caps lock and friends). Nothing to
 * drive yet, but swallowing it keeps the control pipe from stalling. */
void tud_hid_set_report_cb(uint8_t instance, uint8_t report_id,
                           hid_report_type_t report_type,
                           uint8_t const *buffer, uint16_t bufsize)
{
    (void)instance; (void)report_id; (void)report_type; (void)buffer; (void)bufsize;
}
