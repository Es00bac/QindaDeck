#include "bt_host.h"

#include <stdlib.h>
#include <string.h>

#include "deck_config.h"
#include "esp_hid_gap.h"
#include "esp_hidh.h"
#include "esp_hidh_bluedroid.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "net_link.h"
#include "nvs.h"
#include "remap.h"

static const char *TAG = "bt";

#define BT_MAX_PAIRED 4

typedef struct {
    char addr[18];
    char name[40];
    int addr_type;
    bool used;
    esp_hidh_dev_t *dev;   /* non-NULL while connected */
} paired_t;

static paired_t s_paired[BT_MAX_PAIRED];
static SemaphoreHandle_t s_lock;
static volatile bool s_scanning;
static bool s_ready;

static void fmt_addr(const uint8_t *bda, char *out, size_t len)
{
    snprintf(out, len, "%02x:%02x:%02x:%02x:%02x:%02x",
             bda[0], bda[1], bda[2], bda[3], bda[4], bda[5]);
}

static bool parse_addr(const char *s, uint8_t *bda)
{
    unsigned v[6];
    if (!s || sscanf(s, "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6) {
        return false;
    }
    for (int i = 0; i < 6; i++) {
        if (v[i] > 0xff) {
            return false;
        }
        bda[i] = (uint8_t)v[i];
    }
    return true;
}

/* ------------------------------------------------------------------ persistence */

/* The paired set is small and rarely written, so it lives in NVS as one JSON
 * blob rather than earning its own key layout. */
static void save_paired_locked(void)
{
    cJSON *arr = cJSON_CreateArray();
    for (int i = 0; i < BT_MAX_PAIRED; i++) {
        if (!s_paired[i].used) {
            continue;
        }
        cJSON *o = cJSON_CreateObject();
        cJSON_AddStringToObject(o, "addr", s_paired[i].addr);
        cJSON_AddStringToObject(o, "name", s_paired[i].name);
        cJSON_AddNumberToObject(o, "addr_type", s_paired[i].addr_type);
        cJSON_AddItemToArray(arr, o);
    }
    char *txt = cJSON_PrintUnformatted(arr);
    cJSON_Delete(arr);
    if (!txt) {
        return;
    }
    nvs_handle_t h;
    if (nvs_open("deck", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_str(h, "btdevs", txt);
        nvs_commit(h);
        nvs_close(h);
    }
    free(txt);
}

static void load_paired(void)
{
    nvs_handle_t h;
    if (nvs_open("deck", NVS_READONLY, &h) != ESP_OK) {
        return;
    }
    size_t len = 0;
    if (nvs_get_str(h, "btdevs", NULL, &len) != ESP_OK || len == 0 || len > 2048) {
        nvs_close(h);
        return;
    }
    char *txt = malloc(len);
    if (!txt) {
        nvs_close(h);
        return;
    }
    if (nvs_get_str(h, "btdevs", txt, &len) != ESP_OK) {
        free(txt);
        nvs_close(h);
        return;
    }
    nvs_close(h);

    cJSON *arr = cJSON_Parse(txt);
    free(txt);
    if (!cJSON_IsArray(arr)) {
        cJSON_Delete(arr);
        return;
    }
    int n = cJSON_GetArraySize(arr);
    for (int i = 0; i < n && i < BT_MAX_PAIRED; i++) {
        const cJSON *o = cJSON_GetArrayItem(arr, i);
        const cJSON *a = cJSON_GetObjectItemCaseSensitive(o, "addr");
        if (!cJSON_IsString(a)) {
            continue;
        }
        snprintf(s_paired[i].addr, sizeof(s_paired[i].addr), "%s", a->valuestring);
        const cJSON *nm = cJSON_GetObjectItemCaseSensitive(o, "name");
        snprintf(s_paired[i].name, sizeof(s_paired[i].name), "%s",
                 cJSON_IsString(nm) ? nm->valuestring : "");
        const cJSON *at = cJSON_GetObjectItemCaseSensitive(o, "addr_type");
        s_paired[i].addr_type = cJSON_IsNumber(at) ? at->valueint : 0;
        s_paired[i].used = true;
        ESP_LOGI(TAG, "remembered %s (%s)", s_paired[i].addr, s_paired[i].name);
    }
    cJSON_Delete(arr);
}

static paired_t *find_locked(const char *addr)
{
    for (int i = 0; i < BT_MAX_PAIRED; i++) {
        if (s_paired[i].used && !strcasecmp(s_paired[i].addr, addr)) {
            return &s_paired[i];
        }
    }
    return NULL;
}

/* ------------------------------------------------------------------ events */

static void send_bt_event(const char *state, const char *addr, const char *name,
                          const char *reason)
{
    cJSON *ev = cJSON_CreateObject();
    cJSON_AddNumberToObject(ev, "id", 0);
    cJSON_AddStringToObject(ev, "ev", "bt");
    cJSON_AddStringToObject(ev, "state", state);
    cJSON_AddStringToObject(ev, "addr", addr ? addr : "");
    if (name) {
        cJSON_AddStringToObject(ev, "name", name);
    }
    if (reason) {
        cJSON_AddStringToObject(ev, "reason", reason);
    }
    net_link_send_json(ev);
}

static void hidh_callback(void *args, esp_event_base_t base, int32_t id, void *data)
{
    (void)args; (void)base;
    esp_hidh_event_data_t *p = (esp_hidh_event_data_t *)data;
    char addr[18] = {0};

    switch ((esp_hidh_event_t)id) {
    case ESP_HIDH_OPEN_EVENT: {
        if (p->open.status != ESP_OK) {
            ESP_LOGW(TAG, "open failed: %d", p->open.status);
            send_bt_event("failed", "", NULL, "could not connect");
            break;
        }
        const uint8_t *bda = esp_hidh_dev_bda_get(p->open.dev);
        fmt_addr(bda, addr, sizeof(addr));
        const char *name = esp_hidh_dev_name_get(p->open.dev);
        ESP_LOGI(TAG, "connected %s (%s), usage %d", addr, name ? name : "?",
                 esp_hidh_dev_usage_get(p->open.dev));

        xSemaphoreTake(s_lock, portMAX_DELAY);
        paired_t *e = find_locked(addr);
        if (!e) {
            for (int i = 0; i < BT_MAX_PAIRED; i++) {
                if (!s_paired[i].used) {
                    e = &s_paired[i];
                    memset(e, 0, sizeof(*e));
                    snprintf(e->addr, sizeof(e->addr), "%s", addr);
                    e->used = true;
                    break;
                }
            }
        }
        if (e) {
            e->dev = p->open.dev;
            if (name && *name) {
                snprintf(e->name, sizeof(e->name), "%s", name);
            }
            save_paired_locked();
        }
        xSemaphoreGive(s_lock);

        /* Stale edge state would otherwise synthesise releases against a report
         * from the previous session. */
        remap_reset_device(addr);
        send_bt_event("connected", addr, name, NULL);
        break;
    }
    case ESP_HIDH_INPUT_EVENT: {
        const uint8_t *bda = esp_hidh_dev_bda_get(p->input.dev);
        fmt_addr(bda, addr, sizeof(addr));
        remap_feed(addr, (int)p->input.usage, p->input.report_id,
                   p->input.data, p->input.length);
        break;
    }
    case ESP_HIDH_BATTERY_EVENT: {
        const uint8_t *bda = esp_hidh_dev_bda_get(p->battery.dev);
        fmt_addr(bda, addr, sizeof(addr));
        cJSON *ev = cJSON_CreateObject();
        cJSON_AddNumberToObject(ev, "id", 0);
        cJSON_AddStringToObject(ev, "ev", "bt");
        cJSON_AddStringToObject(ev, "state", "battery");
        cJSON_AddStringToObject(ev, "addr", addr);
        cJSON_AddNumberToObject(ev, "level", p->battery.level);
        net_link_send_json(ev);
        break;
    }
    case ESP_HIDH_CLOSE_EVENT: {
        const uint8_t *bda = esp_hidh_dev_bda_get(p->close.dev);
        fmt_addr(bda, addr, sizeof(addr));
        ESP_LOGI(TAG, "disconnected %s", addr);

        xSemaphoreTake(s_lock, portMAX_DELAY);
        paired_t *e = find_locked(addr);
        if (e) {
            e->dev = NULL;
        }
        xSemaphoreGive(s_lock);

        /* Release anything the device was holding, or a remapped key stays down
         * after the controller wanders out of range. */
        remap_reset_device(addr);
        send_bt_event("disconnected", addr, NULL, NULL);

        /* esp_hidh hands ownership of the device object back on close. */
        esp_hidh_dev_free(p->close.dev);
        break;
    }
    default:
        break;
    }
}

/* ------------------------------------------------------------------ reconnect */

/* Remembered devices are re-opened on a slow loop. A gamepad that is switched
 * off should not cost anything, and one that is switched on should come back
 * without the phone being involved. */
static void reconnect_task(void *arg)
{
    (void)arg;
    /* Let Wi-Fi settle first; both radios share one antenna path and a burst of
     * connection attempts during DHCP just makes everything slower. */
    vTaskDelay(pdMS_TO_TICKS(4000));

    for (;;) {
        if (!s_scanning) {
            for (int i = 0; i < BT_MAX_PAIRED; i++) {
                xSemaphoreTake(s_lock, portMAX_DELAY);
                bool want = s_paired[i].used && s_paired[i].dev == NULL;
                char addr[18];
                int at = 0;
                if (want) {
                    snprintf(addr, sizeof(addr), "%s", s_paired[i].addr);
                    at = s_paired[i].addr_type;
                }
                xSemaphoreGive(s_lock);
                if (!want) {
                    continue;
                }
                uint8_t bda[6];
                if (parse_addr(addr, bda)) {
                    ESP_LOGD(TAG, "trying %s", addr);
                    esp_hidh_dev_open(bda, ESP_HID_TRANSPORT_BLE, (uint8_t)at);
                }
                vTaskDelay(pdMS_TO_TICKS(1500));
            }
        }
        vTaskDelay(pdMS_TO_TICKS(10000));
    }
}

/* ------------------------------------------------------------------ API */

void bt_host_init(void)
{
    s_lock = xSemaphoreCreateMutex();
    load_paired();

    esp_err_t e = esp_hid_gap_init(HIDH_BLE_MODE);
    if (e != ESP_OK) {
        ESP_LOGE(TAG, "GAP init failed: %s", esp_err_to_name(e));
        return;
    }
    ESP_ERROR_CHECK(esp_ble_gattc_register_callback(esp_hidh_gattc_event_handler));

    esp_hidh_config_t cfg = {
        .callback = hidh_callback,
        .event_stack_size = 4096,
        .callback_arg = NULL,
    };
    e = esp_hidh_init(&cfg);
    if (e != ESP_OK) {
        ESP_LOGE(TAG, "HID host init failed: %s", esp_err_to_name(e));
        return;
    }
    s_ready = true;
    xTaskCreate(reconnect_task, "deck_btrc", 4096, NULL, 4, NULL);
    ESP_LOGI(TAG, "BLE HID host ready (BLE only; this chip has no Bluetooth Classic)");
}

bool bt_host_scanning(void)
{
    return s_scanning;
}

cJSON *bt_host_scan(int seconds)
{
    cJSON *arr = cJSON_CreateArray();
    if (!s_ready) {
        return arr;
    }
    if (seconds < 2) seconds = 2;
    if (seconds > 15) seconds = 15;

    s_scanning = true;
    size_t n = 0;
    esp_hid_scan_result_t *results = NULL;
    esp_err_t e = esp_hid_scan((uint32_t)seconds, &n, &results);
    s_scanning = false;
    if (e != ESP_OK) {
        ESP_LOGW(TAG, "scan failed: %s", esp_err_to_name(e));
        return arr;
    }

    for (esp_hid_scan_result_t *r = results; r; r = r->next) {
        if (r->transport != ESP_HID_TRANSPORT_BLE) {
            continue;   /* cannot be reached on this chip */
        }
        char addr[18];
        fmt_addr(r->bda, addr, sizeof(addr));
        cJSON *o = cJSON_CreateObject();
        cJSON_AddStringToObject(o, "addr", addr);
        cJSON_AddStringToObject(o, "name", r->name ? r->name : "");
        cJSON_AddNumberToObject(o, "rssi", r->rssi);
        cJSON_AddNumberToObject(o, "usage", r->usage);
        cJSON_AddNumberToObject(o, "addr_type", r->ble.addr_type);
        cJSON_AddNumberToObject(o, "appearance", r->ble.appearance);
        /* A friendly hint so the app can label the row without a usage table. */
        const char *what = "device";
        if (r->usage == ESP_HID_USAGE_KEYBOARD) what = "keyboard";
        else if (r->usage == ESP_HID_USAGE_MOUSE) what = "mouse";
        else if (r->usage == ESP_HID_USAGE_GAMEPAD) what = "gamepad";
        else if (r->usage == ESP_HID_USAGE_JOYSTICK) what = "joystick";
        cJSON_AddStringToObject(o, "what", what);
        cJSON_AddItemToArray(arr, o);
    }
    esp_hid_scan_results_free(results);
    ESP_LOGI(TAG, "scan found %d BLE HID device(s)", cJSON_GetArraySize(arr));
    return arr;
}

bool bt_host_pair(const char *addr, int addr_type, const char *name,
                  char *err, size_t errlen)
{
    if (!s_ready) {
        snprintf(err, errlen, "Bluetooth is not running");
        return false;
    }
    uint8_t bda[6];
    if (!parse_addr(addr, bda)) {
        snprintf(err, errlen, "malformed address");
        return false;
    }

    xSemaphoreTake(s_lock, portMAX_DELAY);
    paired_t *e = find_locked(addr);
    if (!e) {
        for (int i = 0; i < BT_MAX_PAIRED; i++) {
            if (!s_paired[i].used) {
                e = &s_paired[i];
                memset(e, 0, sizeof(*e));
                e->used = true;
                snprintf(e->addr, sizeof(e->addr), "%s", addr);
                break;
            }
        }
    }
    if (!e) {
        xSemaphoreGive(s_lock);
        snprintf(err, errlen, "already remembering %d devices", BT_MAX_PAIRED);
        return false;
    }
    if (addr_type >= 0) {
        e->addr_type = addr_type;
    }
    if (name && *name) {
        snprintf(e->name, sizeof(e->name), "%s", name);
    }
    int at = e->addr_type;
    save_paired_locked();
    xSemaphoreGive(s_lock);

    /* The OPEN event reports the real outcome; this only starts the attempt, so
     * the app gets an immediate ack and then a `bt` event. */
    esp_hidh_dev_open(bda, ESP_HID_TRANSPORT_BLE, (uint8_t)at);
    return true;
}

bool bt_host_forget(const char *addr)
{
    xSemaphoreTake(s_lock, portMAX_DELAY);
    paired_t *e = find_locked(addr);
    esp_hidh_dev_t *dev = e ? e->dev : NULL;
    if (e) {
        e->used = false;
        e->dev = NULL;
        save_paired_locked();
    }
    xSemaphoreGive(s_lock);
    if (!e) {
        return false;
    }
    if (dev) {
        esp_hidh_dev_close(dev);
    }
    uint8_t bda[6];
    if (parse_addr(addr, bda)) {
        /* Drop the bond too, so re-pairing starts clean rather than reusing keys
         * the peripheral may itself have forgotten. */
        esp_ble_remove_bond_device(bda);
    }
    remap_reset_device(addr);
    ESP_LOGI(TAG, "forgot %s", addr);
    return true;
}

cJSON *bt_host_list(void)
{
    cJSON *arr = cJSON_CreateArray();
    xSemaphoreTake(s_lock, portMAX_DELAY);
    for (int i = 0; i < BT_MAX_PAIRED; i++) {
        if (!s_paired[i].used) {
            continue;
        }
        cJSON *o = cJSON_CreateObject();
        cJSON_AddStringToObject(o, "addr", s_paired[i].addr);
        cJSON_AddStringToObject(o, "name", s_paired[i].name);
        cJSON_AddNumberToObject(o, "addr_type", s_paired[i].addr_type);
        cJSON_AddBoolToObject(o, "connected", s_paired[i].dev != NULL);
        cJSON_AddItemToArray(arr, o);
    }
    xSemaphoreGive(s_lock);
    return arr;
}
