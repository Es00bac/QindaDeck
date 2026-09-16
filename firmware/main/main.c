/* QindaDeck firmware entry point.
 *
 * Bring-up order matters: USB first so the host sees the device promptly even if
 * Wi-Fi is slow or absent, then storage, then the engine, then the network. */
#include <string.h>

#include "action.h"
#include "bt_host.h"
#include "bt_prov.h"
#include "deck_config.h"
#include "displays.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_spiffs.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "macro.h"
#include "net_link.h"
#include "nvs_flash.h"
#include "profiles.h"
#include "protocol.h"
#include "sdcard.h"
#include "remap.h"
#include "provision.h"
#include "usb_hid.h"

static const char *TAG = "qindadeck";

static void mount_storage(void)
{
    esp_vfs_spiffs_conf_t cfg = {
        .base_path = DECK_FS_BASE,
        .partition_label = "storage",
        .max_files = 8,
        .format_if_mount_failed = true,
    };
    esp_err_t e = esp_vfs_spiffs_register(&cfg);
    if (e != ESP_OK) {
        ESP_LOGE(TAG, "SPIFFS mount failed: %s", esp_err_to_name(e));
        return;
    }
    size_t total = 0, used = 0;
    if (esp_spiffs_info("storage", &total, &used) == ESP_OK) {
        ESP_LOGI(TAG, "storage %u/%u bytes used", (unsigned)used, (unsigned)total);
    }
}

void app_main(void)
{
    esp_err_t e = nvs_flash_init();
    if (e == ESP_ERR_NVS_NO_FREE_PAGES || e == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        e = nvs_flash_init();
    }
    ESP_ERROR_CHECK(e);

    /* A card already in the slot becomes the MSC LUN at enumeration time; the
     * retry task picks up cards inserted later. Probing first is what lets a
     * boot-menu BIOS see the drive on the very first connect. */
    sdcard_init();
    sdcard_start_retry_task();

    /* The host should enumerate us as a keyboard whether or not there is a
     * network to be found, so USB comes up before Wi-Fi. */
    usb_hid_init();

    mount_storage();
    displays_init();
    macro_engine_init();
    action_init();
    profiles_init();
    remap_init();
    protocol_init();
    net_link_init();
    provision_init();
    /* Bluetooth last: it is the heaviest subsystem and the least urgent, and
     * bringing it up after Wi-Fi keeps the coexistence arbiter's job simple. */
    bt_host_init();
    bt_prov_init();
    /* With no Wi-Fi credentials the deck exists to be provisioned, so the BLE
     * window stays open until that happens; otherwise it opens on demand via
     * the bt_prov op. */
    if (!net_link_has_credentials()) {
        bt_prov_start(0);
    }

    ESP_LOGI(TAG, "QindaDeck %s ready", DECK_FW_VERSION);

    /* Report USB attach/detach so the app can grey out the deck when the board
     * is powered but not plugged into a host. */
    bool last = usb_hid_mounted();
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(250));
        bool now = usb_hid_mounted();
        if (now != last) {
            last = now;
            ESP_LOGI(TAG, "USB %s", now ? "mounted" : "detached");
            cJSON *ev = cJSON_CreateObject();
            cJSON_AddNumberToObject(ev, "id", 0);
            cJSON_AddStringToObject(ev, "ev", "usb");
            cJSON_AddBoolToObject(ev, "mounted", now);
            net_link_send_json(ev);
            if (!now) {
                /* A detach mid-macro would otherwise leave phantom held keys the
                 * next time the cable goes back in. */
                macro_stop();
                usb_hid_forget_abs();
            }
        }
    }
}
