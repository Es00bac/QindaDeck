#include "provision.h"

#include <stdlib.h>
#include <string.h>

#include "cJSON.h"
#include "deck_config.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "net_link.h"
#include "profiles.h"
#include "usb_hid.h"

static const char *TAG = "provision";

/* Deliberately one self-contained page: the provisioning AP has no route to a
 * CDN, so every byte the browser needs has to come from here. */
static const char PAGE[] =
    "<!doctype html><meta charset=utf-8>"
    "<meta name=viewport content='width=device-width,initial-scale=1'>"
    "<title>QindaDeck setup</title><style>"
    ":root{color-scheme:dark}"
    "body{margin:0;background:#12151c;color:#e8ecf4;"
    "font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}"
    ".w{max-width:26rem;margin:0 auto;padding:2rem 1.25rem}"
    "h1{font-size:1.35rem;margin:0 0 .25rem}"
    "p.s{color:#93a0b8;margin:0 0 1.5rem}"
    "label{display:block;font-size:.8rem;text-transform:uppercase;"
    "letter-spacing:.06em;color:#93a0b8;margin:1rem 0 .35rem}"
    "input{width:100%;box-sizing:border-box;padding:.7rem .8rem;border-radius:.5rem;"
    "border:1px solid #2b3344;background:#1a1f2b;color:#e8ecf4;font-size:1rem}"
    "button{width:100%;margin-top:1.5rem;padding:.8rem;border:0;border-radius:.5rem;"
    "background:#4f8df5;color:#fff;font-size:1rem;font-weight:600}"
    "button:disabled{opacity:.6}"
    ".m{margin-top:1rem;padding:.7rem .8rem;border-radius:.5rem;font-size:.9rem;display:none}"
    ".ok{background:#16794f33;border:1px solid #16794f;color:#8ee4b8}"
    ".no{background:#8a2f2833;border:1px solid #8a2f28;color:#f2b0ab}"
    "dl{display:grid;grid-template-columns:auto 1fr;gap:.3rem .8rem;font-size:.9rem;"
    "margin:1.5rem 0 0;padding-top:1.25rem;border-top:1px solid #2b3344}"
    "dt{color:#93a0b8}dd{margin:0;font-variant-numeric:tabular-nums}"
    "</style><div class=w>"
    "<h1>QindaDeck</h1><p class=s>Join the deck to your Wi-Fi network.</p>"
    "<form id=f><label for=s>Network name</label>"
    "<input id=s name=ssid autocapitalize=off autocorrect=off required>"
    "<label for=p>Password</label><input id=p name=pass type=password>"
    "<button type=submit>Save and restart</button></form>"
    "<div class=m id=m></div>"
    "<dl id=st></dl></div><script>"
    "const m=document.getElementById('m');"
    "function say(t,cls){m.textContent=t;m.className='m '+cls;m.style.display='block'}"
    "document.getElementById('f').onsubmit=async e=>{e.preventDefault();"
    "const b=e.target.querySelector('button');b.disabled=true;"
    "try{const r=await fetch('/api/wifi',{method:'POST',"
    "headers:{'content-type':'application/json'},"
    "body:JSON.stringify({ssid:s.value,pass:p.value})});"
    "const j=await r.json();"
    "if(j.ok){say('Saved. The deck is restarting and will join '+s.value+'.','ok')}"
    "else{say(j.err||'Rejected.','no');b.disabled=false}}"
    "catch(err){say('Could not reach the deck.','no');b.disabled=false}};"
    "fetch('/api/status').then(r=>r.json()).then(j=>{"
    "document.getElementById('st').innerHTML="
    "'<dt>Firmware</dt><dd>'+j.fw+'</dd>'+"
    "'<dt>Address</dt><dd>'+j.ip+'</dd>'+"
    "'<dt>USB</dt><dd>'+(j.usb?'connected':'not connected')+'</dd>'+"
    "'<dt>Profiles</dt><dd>'+j.profiles+'</dd>'}).catch(()=>{});"
    "</script>";

static esp_err_t get_root(httpd_req_t *req)
{
    httpd_resp_set_type(req, "text/html; charset=utf-8");
    return httpd_resp_send(req, PAGE, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t get_status(httpd_req_t *req)
{
    char ip[16], name[40];
    net_link_ip(ip, sizeof(ip));
    net_link_get_name(name, sizeof(name));

    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "fw", DECK_FW_VERSION);
    cJSON_AddStringToObject(o, "ip", ip);
    cJSON_AddStringToObject(o, "name", name);
    cJSON_AddBoolToObject(o, "usb", usb_hid_mounted());
    cJSON_AddBoolToObject(o, "ap", net_link_ap_mode());
    cJSON_AddNumberToObject(o, "profiles", profiles_count());
    cJSON_AddNumberToObject(o, "port", DECK_TCP_PORT);
    char *txt = cJSON_PrintUnformatted(o);
    cJSON_Delete(o);

    httpd_resp_set_type(req, "application/json");
    esp_err_t e = httpd_resp_send(req, txt ? txt : "{}", HTTPD_RESP_USE_STRLEN);
    free(txt);
    return e;
}

static esp_err_t post_wifi(httpd_req_t *req)
{
    if (req->content_len > 512) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "body too large");
        return ESP_FAIL;
    }
    char buf[513];
    int got = httpd_req_recv(req, buf, req->content_len);
    if (got <= 0) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "no body");
        return ESP_FAIL;
    }
    buf[got] = 0;

    cJSON *in = cJSON_Parse(buf);
    const cJSON *ssid = in ? cJSON_GetObjectItemCaseSensitive(in, "ssid") : NULL;
    const cJSON *pass = in ? cJSON_GetObjectItemCaseSensitive(in, "pass") : NULL;

    cJSON *out = cJSON_CreateObject();
    bool ok = cJSON_IsString(ssid) &&
              net_link_wifi_set(ssid->valuestring,
                                cJSON_IsString(pass) ? pass->valuestring : "");
    cJSON_AddBoolToObject(out, "ok", ok);
    if (!ok) {
        cJSON_AddStringToObject(out, "err", "network name missing or too long");
    }
    char *txt = cJSON_PrintUnformatted(out);
    cJSON_Delete(out);
    cJSON_Delete(in);

    httpd_resp_set_type(req, "application/json");
    httpd_resp_send(req, txt ? txt : "{\"ok\":false}", HTTPD_RESP_USE_STRLEN);
    free(txt);

    if (ok) {
        ESP_LOGI(TAG, "credentials stored over HTTP, restarting");
        /* The reply is already flushed; a short grace period lets the socket
         * close cleanly before the reset. */
        vTaskDelay(pdMS_TO_TICKS(600));
        esp_restart();
    }
    return ESP_OK;
}

void provision_init(void)
{
    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    cfg.lru_purge_enable = true;
    cfg.max_uri_handlers = 6;

    httpd_handle_t srv = NULL;
    if (httpd_start(&srv, &cfg) != ESP_OK) {
        ESP_LOGW(TAG, "HTTP server did not start");
        return;
    }

    static const httpd_uri_t root = {.uri = "/", .method = HTTP_GET, .handler = get_root};
    static const httpd_uri_t status = {
        .uri = "/api/status", .method = HTTP_GET, .handler = get_status};
    static const httpd_uri_t wifi = {
        .uri = "/api/wifi", .method = HTTP_POST, .handler = post_wifi};

    httpd_register_uri_handler(srv, &root);
    httpd_register_uri_handler(srv, &status);
    httpd_register_uri_handler(srv, &wifi);
    ESP_LOGI(TAG, "setup page on port 80");
}
