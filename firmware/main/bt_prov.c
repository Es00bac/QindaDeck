#include "bt_prov.h"

#include <string.h>

#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "net_link.h"

static const char *TAG = "bt_prov";

/* UUIDs, canonical form for the phone side:
 *   service 51DA1000-0DEC-4000-A000-0000000DECA0
 *   ssid    51DA1001-0DEC-4000-A000-0000000DECA0
 *   pass    51DA1002-0DEC-4000-A000-0000000DECA0
 *   apply   51DA1003-0DEC-4000-A000-0000000DECA0
 *   state   51DA1004-0DEC-4000-A000-0000000DECA0
 * Bluedroid wants the 16 bytes little-endian. */
#define UUID128(v)                                                        \
    {                                                                     \
        0xA0, 0xCA, 0x0D, 0x00, 0x00, 0x00, 0xA0, 0x00, 0x40, 0x00, 0xEC, \
        0x0D, (uint8_t)(v), 0x10, 0xDA, 0x51                              \
    }

static const uint8_t SVC_UUID[16]  = UUID128(0x00);
static const uint8_t SSID_UUID[16] = UUID128(0x01);
static const uint8_t PASS_UUID[16] = UUID128(0x02);
static const uint8_t APPLY_UUID[16] = UUID128(0x03);
static const uint8_t STATE_UUID[16] = UUID128(0x04);

#define PROV_APP_ID  0x51

/* STATE values the phone can read/watch. */
enum { ST_IDLE = 0, ST_SSID = 1, ST_READY = 2, ST_APPLYING = 3 };

static esp_gatt_if_t s_gatts_if = ESP_GATT_IF_NONE;
static uint16_t s_conn_id = 0xFFFF;
static uint16_t s_svc_handle;
static uint16_t s_ssid_handle, s_pass_handle, s_apply_handle, s_state_handle, s_cccd_handle;

static char s_ssid[33];
static char s_pass[65];
static uint8_t s_state = ST_IDLE;
static esp_timer_handle_t s_window_timer;
static bool s_adv_on;

/* ------------------------------------------------------------ window */

static void close_window(void)
{
    if (s_adv_on) {
        esp_ble_gap_stop_advertising();
        s_adv_on = false;
    }
    if (s_window_timer) {
        esp_timer_stop(s_window_timer);
    }
    s_ssid[0] = 0;
    s_pass[0] = 0;
    s_state = ST_IDLE;
    ESP_LOGI(TAG, "provisioning window closed");
}

static void window_expired(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "provisioning window expired");
    close_window();
}

bool bt_prov_active(void)
{
    return s_adv_on;
}

/* ------------------------------------------------------------ advertising */

static esp_ble_adv_params_t s_adv_params = {
    .adv_int_min = 0x60,
    .adv_int_max = 0xA0,
    .adv_type = ADV_TYPE_IND,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .channel_map = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};

void bt_prov_start(int seconds)
{
    if (!s_gatts_if) {
        ESP_LOGW(TAG, "not initialised yet");
        return;
    }

    char name[40];
    net_link_get_name(name, sizeof(name));

    /* Complete name + flags in the adv PDU; the 128-bit service UUID goes in
     * the scan response so the phone can filter on it. */
    uint8_t adv_raw[31];
    int n = 0;
    size_t nl = strlen(name);
    if (nl > 26) nl = 26;
    adv_raw[n++] = 2;
    adv_raw[n++] = 0x01;  /* flags */
    adv_raw[n++] = 0x06;
    adv_raw[n++] = (uint8_t)(nl + 1);
    adv_raw[n++] = 0x09;  /* complete name */
    memcpy(adv_raw + n, name, nl);
    n += (int)nl;

    esp_ble_adv_data_t adv = {0};
    adv.set_scan_rsp = false;
    adv.include_name = false;
    adv.include_txpower = false;
    adv.min_interval = 0;
    adv.max_interval = 0;
    adv.appearance = 0;
    adv.manufacturer_len = 0;
    adv.p_manufacturer_data = NULL;
    adv.service_data_len = 0;
    adv.p_service_data = NULL;
    adv.service_uuid_len = 0;
    adv.p_service_uuid = NULL;
    adv.flag = 0;
    /* Raw bytes already carry flags+name; use custom data path. */
    esp_ble_gap_config_adv_data_raw(adv_raw, (uint8_t)n);

    uint8_t rsp_raw[31];
    int m = 0;
    rsp_raw[m++] = 17;
    rsp_raw[m++] = 0x07;  /* complete 128-bit service UUID list */
    memcpy(rsp_raw + m, SVC_UUID, 16);
    m += 16;
    esp_ble_gap_config_scan_rsp_data_raw(rsp_raw, (uint8_t)m);
    (void)adv;

    esp_err_t err = esp_ble_gap_start_advertising(&s_adv_params);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "advertising start failed: %s", esp_err_to_name(err));
        return;
    }
    s_adv_on = true;

    if (seconds > 0) {
        if (!s_window_timer) {
            const esp_timer_create_args_t args = {
                .callback = window_expired,
                .name = "prov_window",
            };
            esp_timer_create(&args, &s_window_timer);
        }
        esp_timer_stop(s_window_timer);
        esp_timer_start_once(s_window_timer, (uint64_t)seconds * 1000000ULL);
    }
    ESP_LOGI(TAG, "provisioning window open%s", seconds ? "" : " (until reboot)");
}

void bt_prov_stop(void)
{
    close_window();
}

/* ------------------------------------------------------------ apply + reboot */

static void reboot_cb(void *arg)
{
    (void)arg;
    esp_restart();
}

static void apply_credentials(void)
{
    if (!s_ssid[0]) {
        return;
    }
    s_state = ST_APPLYING;
    if (s_cccd_handle && s_conn_id != 0xFFFF && s_gatts_if) {
        esp_ble_gatts_send_indicate(s_gatts_if, s_conn_id, s_state_handle,
                                    1, &s_state, false);
    }
    ESP_LOGI(TAG, "storing Wi-Fi '%s' over BLE, rebooting", s_ssid);
    net_link_wifi_set(s_ssid, s_pass);

    static esp_timer_handle_t reb;
    if (!reb) {
        const esp_timer_create_args_t args = {
            .callback = reboot_cb,
            .name = "prov_reboot",
        };
        esp_timer_create(&args, &reb);
    }
    /* Give the write response and the notify a moment on the air first. */
    esp_timer_start_once(reb, 800 * 1000);
}

/* ------------------------------------------------------------ GATTS events */

static void gatts_cb(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if,
                     esp_ble_gatts_cb_param_t *p)
{
    switch (event) {
    case ESP_GATTS_REG_EVT:
        s_gatts_if = gatts_if;
        {
            esp_gatt_srvc_id_t svc = {
                .is_primary = true,
                .id = {
                    .inst_id = 0,
                    .uuid = { .len = ESP_UUID_LEN_128 },
                },
            };
            memcpy(svc.id.uuid.uuid.uuid128, SVC_UUID, 16);
            /* svc(2) + ssid decl+val(2) + pass(2) + apply(2) + state decl+val+ccc(3) */
            esp_ble_gatts_create_service(gatts_if, &svc, 11);
        }
        break;

    case ESP_GATTS_CREATE_EVT:
        s_svc_handle = p->create.service_handle;
        esp_ble_gatts_start_service(s_svc_handle);
        {
            esp_bt_uuid_t u = { .len = ESP_UUID_LEN_128 };
            esp_gatt_perm_t wr = ESP_GATT_PERM_WRITE;

            memcpy(u.uuid.uuid128, SSID_UUID, 16);
            esp_attr_value_t vs = { .attr_max_len = 32, .attr_len = 0, .attr_value = (uint8_t *)s_ssid };
            esp_ble_gatts_add_char(s_svc_handle, &u, wr,
                                   ESP_GATT_CHAR_PROP_BIT_WRITE, &vs, NULL);

            memcpy(u.uuid.uuid128, PASS_UUID, 16);
            esp_attr_value_t vp = { .attr_max_len = 64, .attr_len = 0, .attr_value = (uint8_t *)s_pass };
            esp_ble_gatts_add_char(s_svc_handle, &u, wr,
                                   ESP_GATT_CHAR_PROP_BIT_WRITE, &vp, NULL);

            memcpy(u.uuid.uuid128, APPLY_UUID, 16);
            uint8_t zero = 0;
            esp_attr_value_t va = { .attr_max_len = 1, .attr_len = 1, .attr_value = &zero };
            esp_ble_gatts_add_char(s_svc_handle, &u, wr,
                                   ESP_GATT_CHAR_PROP_BIT_WRITE, &va, NULL);

            memcpy(u.uuid.uuid128, STATE_UUID, 16);
            esp_attr_value_t vt = { .attr_max_len = 1, .attr_len = 1, .attr_value = &s_state };
            esp_ble_gatts_add_char(s_svc_handle, &u, ESP_GATT_PERM_READ,
                                   ESP_GATT_CHAR_PROP_BIT_READ | ESP_GATT_CHAR_PROP_BIT_NOTIFY,
                                   &vt, NULL);
        }
        break;

    case ESP_GATTS_ADD_CHAR_EVT: {
        const uint8_t *u = p->add_char.char_uuid.uuid.uuid128;
        if (p->add_char.char_uuid.len == ESP_UUID_LEN_128) {
            if (!memcmp(u, SSID_UUID, 16))       s_ssid_handle = p->add_char.attr_handle;
            else if (!memcmp(u, PASS_UUID, 16))  s_pass_handle = p->add_char.attr_handle;
            else if (!memcmp(u, APPLY_UUID, 16)) s_apply_handle = p->add_char.attr_handle;
            else if (!memcmp(u, STATE_UUID, 16)) {
                s_state_handle = p->add_char.attr_handle;
                esp_bt_uuid_t cccd = { .len = ESP_UUID_LEN_16,
                                       .uuid = { .uuid16 = ESP_GATT_UUID_CHAR_CLIENT_CONFIG } };
                esp_gatt_perm_t rw = ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE;
                esp_ble_gatts_add_char_descr(s_svc_handle, &cccd, rw, NULL, NULL);
            }
        }
        break;
    }

    case ESP_GATTS_ADD_CHAR_DESCR_EVT:
        s_cccd_handle = p->add_char_descr.attr_handle;
        break;

    case ESP_GATTS_CONNECT_EVT:
        s_conn_id = p->connect.conn_id;
        ESP_LOGI(TAG, "phone connected for provisioning");
        break;

    case ESP_GATTS_DISCONNECT_EVT:
        s_conn_id = 0xFFFF;
        /* The window stays open so the phone can come back within it. */
        break;

    case ESP_GATTS_WRITE_EVT: {
        uint16_t h = p->write.handle;
        const uint8_t *d = p->write.value;
        uint16_t len = p->write.len;
        if (h == s_ssid_handle && len > 0 && len <= 32) {
            memcpy(s_ssid, d, len);
            s_ssid[len] = 0;
            s_state = ST_SSID;
            ESP_LOGI(TAG, "ssid staged: '%s'", s_ssid);
        } else if (h == s_pass_handle && len <= 64) {
            memcpy(s_pass, d, len);
            s_pass[len] = 0;
            if (s_state < ST_READY) s_state = ST_READY;
            ESP_LOGI(TAG, "password staged (%u chars)", (unsigned)len);
        } else if (h == s_apply_handle && len >= 1 && d[0] == 1) {
            apply_credentials();
        }
        if (p->write.need_rsp) {
            esp_ble_gatts_send_response(gatts_if, p->write.conn_id,
                                        p->write.trans_id, ESP_GATT_OK, NULL);
        }
        break;
    }

    case ESP_GATTS_READ_EVT:
        if (p->read.handle == s_state_handle && p->read.need_rsp) {
            esp_gatt_rsp_t rsp = {0};
            rsp.attr_value.len = 1;
            rsp.attr_value.value[0] = s_state;
            rsp.attr_value.handle = s_state_handle;
            esp_ble_gatts_send_response(gatts_if, p->read.conn_id,
                                        p->read.trans_id, ESP_GATT_OK, &rsp);
        }
        break;

    default:
        break;
    }
}

void bt_prov_init(void)
{
    ESP_ERROR_CHECK(esp_ble_gatts_register_callback(gatts_cb));
    ESP_ERROR_CHECK(esp_ble_gatts_app_register(PROV_APP_ID));
    ESP_LOGI(TAG, "BLE provisioning service registered");
}
