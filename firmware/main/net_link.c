#include "net_link.h"

#include <errno.h>
#include <string.h>

#include <lwip/netdb.h>
#include <lwip/sockets.h>

#include "deck_config.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "mdns.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "protocol.h"

static const char *TAG = "net";

static bool s_online;
static bool s_ap_mode;
static char s_ip[16] = "0.0.0.0";
static char s_name[40] = "QindaDeck";
static char s_id[8];

static int s_client = -1;
static SemaphoreHandle_t s_send_lock;
static int s_retry;

/* ------------------------------------------------------------------ nvs */

static bool nvs_get_string(const char *ns, const char *key, char *out, size_t len)
{
    nvs_handle_t h;
    if (nvs_open(ns, NVS_READONLY, &h) != ESP_OK) {
        return false;
    }
    size_t l = len;
    esp_err_t e = nvs_get_str(h, key, out, &l);
    nvs_close(h);
    return e == ESP_OK && out[0] != 0;
}

static bool nvs_set_string(const char *ns, const char *key, const char *val)
{
    nvs_handle_t h;
    if (nvs_open(ns, NVS_READWRITE, &h) != ESP_OK) {
        return false;
    }
    esp_err_t e = nvs_set_str(h, key, val);
    if (e == ESP_OK) {
        e = nvs_commit(h);
    }
    nvs_close(h);
    return e == ESP_OK;
}

bool net_link_has_credentials(void)
{
    char ssid[33] = {0};
    return nvs_get_string("wifi", "ssid", ssid, sizeof(ssid));
}

bool net_link_wifi_set(const char *ssid, const char *pass)
{
    if (!ssid || !*ssid || strlen(ssid) > 32) {
        return false;
    }
    if (pass && strlen(pass) > 63) {
        return false;
    }
    return nvs_set_string("wifi", "ssid", ssid) &&
           nvs_set_string("wifi", "pass", pass ? pass : "");
}

void net_link_wifi_forget(void)
{
    nvs_handle_t h;
    if (nvs_open("wifi", NVS_READWRITE, &h) == ESP_OK) {
        nvs_erase_key(h, "ssid");
        nvs_erase_key(h, "pass");
        nvs_commit(h);
        nvs_close(h);
    }
}

void net_link_get_name(char *out, size_t len)
{
    snprintf(out, len, "%s", s_name);
}

bool net_link_set_name(const char *name)
{
    if (!name || !*name || strlen(name) > 39) {
        return false;
    }
    snprintf(s_name, sizeof(s_name), "%s", name);
    nvs_set_string("deck", "name", s_name);
    mdns_service_txt_item_set(DECK_MDNS_SERVICE, DECK_MDNS_PROTO, "name", s_name);
    return true;
}

void net_link_ip(char *out, size_t len)
{
    snprintf(out, len, "%s", s_ip);
}

bool net_link_online(void)
{
    return s_online;
}

bool net_link_ap_mode(void)
{
    return s_ap_mode;
}

/* ------------------------------------------------------------------ framing */

static bool send_all(int fd, const uint8_t *buf, size_t len)
{
    size_t off = 0;
    while (off < len) {
        int n = send(fd, buf + off, len - off, 0);
        if (n <= 0) {
            if (n < 0 && (errno == EINTR || errno == EAGAIN)) {
                continue;
            }
            return false;
        }
        off += (size_t)n;
    }
    return true;
}

void net_link_send_json(cJSON *obj)
{
    if (!obj) {
        return;
    }
    char *txt = cJSON_PrintUnformatted(obj);
    cJSON_Delete(obj);
    if (!txt) {
        return;
    }
    size_t len = strlen(txt);

    xSemaphoreTake(s_send_lock, portMAX_DELAY);
    int fd = s_client;
    if (fd >= 0 && len + 1 <= DECK_MAX_FRAME) {
        uint8_t hdr[5];
        uint32_t flen = (uint32_t)len + 1;
        hdr[0] = (uint8_t)(flen >> 24);
        hdr[1] = (uint8_t)(flen >> 16);
        hdr[2] = (uint8_t)(flen >> 8);
        hdr[3] = (uint8_t)flen;
        hdr[4] = DECK_FRAME_JSON;
        if (!send_all(fd, hdr, sizeof(hdr)) ||
            !send_all(fd, (const uint8_t *)txt, len)) {
            ESP_LOGW(TAG, "client write failed, dropping it");
            close(fd);
            s_client = -1;
        }
    }
    xSemaphoreGive(s_send_lock);
    free(txt);
}

static void serve_client(int fd)
{
    int one = 1;
    /* Nagle would add tens of milliseconds to every tap; a control link cares
     * far more about latency than about packet count. */
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    struct timeval tv = {.tv_sec = 30, .tv_usec = 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    uint8_t hdr[5];
    uint8_t *payload = NULL;
    size_t payload_cap = 0;

    for (;;) {
        /* Header. */
        size_t got = 0;
        while (got < sizeof(hdr)) {
            int n = recv(fd, hdr + got, sizeof(hdr) - got, 0);
            if (n == 0) {
                goto done;
            }
            if (n < 0) {
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    /* Idle. A phone that has gone to sleep will be replaced by
                     * its own reconnect, so just keep waiting. */
                    continue;
                }
                goto done;
            }
            got += (size_t)n;
        }

        uint32_t flen = ((uint32_t)hdr[0] << 24) | ((uint32_t)hdr[1] << 16) |
                        ((uint32_t)hdr[2] << 8) | (uint32_t)hdr[3];
        uint8_t type = hdr[4];
        if (flen == 0 || flen > DECK_MAX_FRAME) {
            ESP_LOGW(TAG, "frame length %" PRIu32 " out of range", flen);
            goto done;
        }
        size_t body = flen - 1;

        if (body > payload_cap) {
            uint8_t *n = realloc(payload, body + 1);
            if (!n) {
                ESP_LOGE(TAG, "cannot allocate %u byte frame", (unsigned)body);
                goto done;
            }
            payload = n;
            payload_cap = body;
        }
        got = 0;
        while (got < body) {
            int n = recv(fd, payload + got, body - got, 0);
            if (n == 0) {
                goto done;
            }
            if (n < 0) {
                if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
                goto done;
            }
            got += (size_t)n;
        }
        if (payload) {
            payload[body] = 0;
        }

        if (type == DECK_FRAME_PING) {
            continue;
        }
        protocol_handle_frame(type, payload, body);
    }

done:
    free(payload);
    xSemaphoreTake(s_send_lock, portMAX_DELAY);
    if (s_client == fd) {
        s_client = -1;
    }
    xSemaphoreGive(s_send_lock);
    close(fd);
    ESP_LOGI(TAG, "client gone");
}

static void tcp_task(void *arg)
{
    (void)arg;
    for (;;) {
        int srv = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (srv < 0) {
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }
        int one = 1;
        setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

        struct sockaddr_in addr = {
            .sin_family = AF_INET,
            .sin_addr.s_addr = htonl(INADDR_ANY),
            .sin_port = htons(DECK_TCP_PORT),
        };
        if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) != 0 ||
            listen(srv, 2) != 0) {
            ESP_LOGE(TAG, "cannot listen on %d: %d", DECK_TCP_PORT, errno);
            close(srv);
            vTaskDelay(pdMS_TO_TICKS(2000));
            continue;
        }
        ESP_LOGI(TAG, "listening on %d", DECK_TCP_PORT);

        for (;;) {
            struct sockaddr_in peer;
            socklen_t plen = sizeof(peer);
            int fd = accept(srv, (struct sockaddr *)&peer, &plen);
            if (fd < 0) {
                if (errno == EINTR) continue;
                break;
            }
            char ip[16];
            inet_ntoa_r(peer.sin_addr, ip, sizeof(ip));
            ESP_LOGI(TAG, "client from %s", ip);

            /* Displace any previous client. */
            xSemaphoreTake(s_send_lock, portMAX_DELAY);
            int old = s_client;
            s_client = fd;
            xSemaphoreGive(s_send_lock);
            if (old >= 0) {
                shutdown(old, SHUT_RDWR);
                close(old);
            }

            protocol_on_connect();
            serve_client(fd);
        }
        close(srv);
    }
}

/* ------------------------------------------------------------------ wifi */

static void start_mdns(void)
{
    if (mdns_init() != ESP_OK) {
        ESP_LOGW(TAG, "mDNS init failed; the app will need a manual address");
        return;
    }
    mdns_hostname_set(DECK_MDNS_HOSTNAME);
    mdns_instance_name_set(s_name);

    mdns_txt_item_t txt[] = {
        {"ver", "1"},
        {"id", s_id},
        {"name", s_name},
    };
    mdns_service_add(NULL, DECK_MDNS_SERVICE, DECK_MDNS_PROTO, DECK_TCP_PORT,
                     txt, sizeof(txt) / sizeof(txt[0]));
    ESP_LOGI(TAG, "mDNS up as %s.local", DECK_MDNS_HOSTNAME);
}

static esp_timer_handle_t s_retry_timer;

static void retry_connect(void *arg)
{
    (void)arg;
    esp_err_t e = esp_wifi_connect();
    if (e != ESP_OK) {
        ESP_LOGW(TAG, "reconnect call failed: %s", esp_err_to_name(e));
    }
}

static void wifi_events(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    (void)arg;
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_err_t e = esp_wifi_connect();
        ESP_LOGI(TAG, "connect requested: %s", esp_err_to_name(e));
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        wifi_event_sta_disconnected_t *d = (wifi_event_sta_disconnected_t *)data;
        s_online = false;
        snprintf(s_ip, sizeof(s_ip), "0.0.0.0");
        ESP_LOGW(TAG, "disconnected from '%s', reason %d", d ? (char *)d->ssid : "?",
                 d ? d->reason : -1);
        /* Back off gently rather than hammering the AP; the deck is useless
         * without the link, so it keeps retrying forever. The retry is armed on a
         * timer instead of sleeping here: this runs on the event loop task, and
         * blocking it would stall every later Wi-Fi and IP event. */
        int delay_ms = s_retry < 5 ? 500 : (s_retry < 20 ? 2000 : 10000);
        s_retry++;
        esp_timer_stop(s_retry_timer);
        esp_timer_start_once(s_retry_timer, (uint64_t)delay_ms * 1000);
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *e = (ip_event_got_ip_t *)data;
        esp_ip4addr_ntoa(&e->ip_info.ip, s_ip, sizeof(s_ip));
        s_online = true;
        s_retry = 0;
        ESP_LOGI(TAG, "online at %s", s_ip);
    }
}

static void start_station(const char *ssid, const char *pass)
{
    const esp_timer_create_args_t targs = {
        .callback = retry_connect,
        .name = "deck_wifi_retry",
    };
    ESP_ERROR_CHECK(esp_timer_create(&targs, &s_retry_timer));

    esp_netif_create_default_wifi_sta();
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                        wifi_events, NULL, NULL));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                                        wifi_events, NULL, NULL));
    wifi_config_t wc = {0};
    snprintf((char *)wc.sta.ssid, sizeof(wc.sta.ssid), "%s", ssid);
    snprintf((char *)wc.sta.password, sizeof(wc.sta.password), "%s", pass);
    wc.sta.threshold.authmode = WIFI_AUTH_OPEN;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wc));
    /* Power save costs latency on every single keypress. */
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_LOGI(TAG, "joining '%s'", ssid);
}

static void start_softap(void)
{
    s_ap_mode = true;
    esp_netif_create_default_wifi_ap();
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));

    wifi_config_t wc = {0};
    snprintf((char *)wc.ap.ssid, sizeof(wc.ap.ssid), DECK_AP_PREFIX "%s", s_id);
    wc.ap.ssid_len = (uint8_t)strlen((char *)wc.ap.ssid);
    snprintf((char *)wc.ap.password, sizeof(wc.ap.password), DECK_AP_PASSWORD);
    wc.ap.channel = 1;
    wc.ap.max_connection = 4;
    wc.ap.authmode = WIFI_AUTH_WPA2_PSK;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &wc));
    ESP_ERROR_CHECK(esp_wifi_start());
    snprintf(s_ip, sizeof(s_ip), "192.168.4.1");
    ESP_LOGI(TAG, "provisioning AP '%s' up at %s", (char *)wc.ap.ssid, s_ip);
}

cJSON *net_link_scan(void)
{
    cJSON *arr = cJSON_CreateArray();

    /* Scanning from AP-only mode returns nothing useful, so switch to APSTA for
     * the duration and put it back afterwards. */
    wifi_mode_t prev;
    esp_wifi_get_mode(&prev);
    if (prev == WIFI_MODE_AP) {
        esp_wifi_set_mode(WIFI_MODE_APSTA);
    }

    wifi_scan_config_t cfg = {.show_hidden = false};
    if (esp_wifi_scan_start(&cfg, true) != ESP_OK) {
        if (prev == WIFI_MODE_AP) {
            esp_wifi_set_mode(prev);
        }
        return arr;
    }
    uint16_t n = 0;
    esp_wifi_scan_get_ap_num(&n);
    if (n > 30) {
        n = 30;
    }
    wifi_ap_record_t *recs = calloc(n, sizeof(wifi_ap_record_t));
    if (recs && esp_wifi_scan_get_ap_records(&n, recs) == ESP_OK) {
        for (int i = 0; i < n; i++) {
            if (recs[i].ssid[0] == 0) {
                continue;
            }
            cJSON *o = cJSON_CreateObject();
            cJSON_AddStringToObject(o, "ssid", (const char *)recs[i].ssid);
            cJSON_AddNumberToObject(o, "rssi", recs[i].rssi);
            cJSON_AddStringToObject(o, "auth",
                recs[i].authmode == WIFI_AUTH_OPEN ? "open" : "psk");
            cJSON_AddItemToArray(arr, o);
        }
    }
    free(recs);
    if (prev == WIFI_MODE_AP) {
        esp_wifi_set_mode(prev);
    }
    return arr;
}

void net_link_init(void)
{
    s_send_lock = xSemaphoreCreateMutex();

    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    snprintf(s_id, sizeof(s_id), "%02X%02X%02X", mac[3], mac[4], mac[5]);

    if (!nvs_get_string("deck", "name", s_name, sizeof(s_name))) {
        snprintf(s_name, sizeof(s_name), "QindaDeck %s", s_id);
    }

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    char ssid[33] = {0}, pass[65] = {0};
    if (nvs_get_string("wifi", "ssid", ssid, sizeof(ssid))) {
        nvs_get_string("wifi", "pass", pass, sizeof(pass));
        start_station(ssid, pass);
    } else {
        start_softap();
    }

    start_mdns();
    xTaskCreate(tcp_task, "deck_tcp", 6144, NULL, 5, NULL);
}
