#include "sdcard.h"

#include <string.h>

#include "deck_config.h"
#include "driver/sdmmc_host.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "sdmmc_cmd.h"
#include "tinyusb_msc.h"

static const char *TAG = "sdcard";

static sdmmc_card_t s_card;
static tinyusb_msc_storage_handle_t s_storage;

bool sdcard_present(void)
{
    return s_storage != NULL;
}

uint64_t sdcard_capacity_bytes(void)
{
    if (!s_storage) {
        return 0;
    }
    /* csd.capacity is in sectors; SD sectors are 512 bytes. */
    return (uint64_t)s_card.csd.capacity * 512ULL;
}

static void msc_event_cb(tinyusb_msc_storage_handle_t handle,
                         tinyusb_msc_event_t *event, void *arg)
{
    (void)handle; (void)arg;
    switch (event->id) {
    case TINYUSB_MSC_EVENT_MOUNT_COMPLETE:
        ESP_LOGI(TAG, "storage %s",
                 event->mount_point == TINYUSB_MSC_STORAGE_MOUNT_USB
                     ? "exposed to USB host" : "taken back by app");
        break;
    case TINYUSB_MSC_EVENT_MOUNT_FAILED:
        ESP_LOGW(TAG, "mount operation failed");
        break;
    default:
        break;
    }
}

esp_err_t sdcard_init(void)
{
    if (s_storage) {
        return ESP_OK;
    }

#if DECK_SD_WIDTH != 1 && DECK_SD_WIDTH != 4
#error "DECK_SD_WIDTH must be 1 or 4"
#endif

    if (DECK_SD_PIN_CLK == GPIO_NUM_NC || DECK_SD_PIN_CMD == GPIO_NUM_NC ||
            DECK_SD_PIN_D0 == GPIO_NUM_NC) {
        ESP_LOGW(TAG, "SD pins are not configured in deck_config.h; slot unused");
        return ESP_ERR_NOT_SUPPORTED;
    }

    /* Default speed rather than high speed: the full-speed USB link is the
     * bottleneck by an order of magnitude either way, and the conservative
     * clock tolerates the less-than-perfect wiring of devkit slots. */
    sdmmc_host_t host = SDMMC_HOST_DEFAULT();
    host.max_freq_khz = SDMMC_FREQ_DEFAULT;
    host.flags = (DECK_SD_WIDTH == 4) ? SDMMC_HOST_FLAG_4BIT : SDMMC_HOST_FLAG_1BIT;

    sdmmc_slot_config_t slot = SDMMC_SLOT_CONFIG_DEFAULT();
    slot.clk = DECK_SD_PIN_CLK;
    slot.cmd = DECK_SD_PIN_CMD;
    slot.d0 = DECK_SD_PIN_D0;
    slot.d1 = DECK_SD_PIN_D1;
    slot.d2 = DECK_SD_PIN_D2;
    slot.d3 = DECK_SD_PIN_D3;
    slot.width = DECK_SD_WIDTH;
    /* Internal pull-ups (~45 kR) backstop boards that omit the external ones
     * the SD spec wants on CMD and the data lines. */
    slot.flags |= SDMMC_SLOT_FLAG_INTERNAL_PULLUP;

    /* esp_vfs_fat_sdmmc_mount would take the card for the app; we only want
     * the raw handle, so init the driver and card directly. */
    esp_err_t err = sdmmc_host_init();
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "host init: %s", esp_err_to_name(err));
        return err;
    }
    err = sdmmc_host_init_slot(SDMMC_HOST_SLOT_1, &slot);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "slot init: %s", esp_err_to_name(err));
        return err;
    }
    err = sdmmc_card_init(&host, &s_card);
    if (err != ESP_OK) {
        /* No card, or a card that does not answer: ordinary absences, not
         * faults, so keep the log quiet about it. */
        ESP_LOGD(TAG, "card probe: %s", esp_err_to_name(err));
        return ESP_ERR_NOT_FOUND;
    }

    ESP_LOGI(TAG, "card: %llu MiB, %d-line mode @ %d kHz",
             (unsigned long long)(sdcard_capacity_bytes() >> 20),
             DECK_SD_WIDTH, host.max_freq_khz);

    /* Hand the whole card to the USB host. do_not_format matters: this card
     * is exactly where a bootable image lives, and an overeager auto-format
     * would destroy it. The deck never mounts the filesystem itself. */
    tinyusb_msc_storage_config_t cfg = {0};
    cfg.medium.card = &s_card;
    cfg.mount_point = TINYUSB_MSC_STORAGE_MOUNT_USB;
    cfg.fat_fs.base_path = (char *)"/sdcard";
    cfg.fat_fs.do_not_format = true;
    cfg.fat_fs.config.max_files = 4;
    cfg.fat_fs.config.format_if_mount_failed = false;

    err = tinyusb_msc_new_storage_sdmmc(&cfg, &s_storage);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "MSC storage: %s", esp_err_to_name(err));
        return err;
    }
    tinyusb_msc_set_storage_callback(msc_event_cb, NULL);
    return ESP_OK;
}

/* ------------------------------------------------------------------ ownership
 *
 * Exactly one side can use the card at a time: the USB host (as a block
 * device) or the firmware (as a FAT filesystem). Concurrent raw-sector and
 * filesystem access is how cards get corrupted, so the app must take the card
 * explicitly — the host sees its drive eject — and release it when done. */

#define DECK_SD_IDLE_RELEASE_US (60 * 1000 * 1000)

static bool s_owner_app;
static int64_t s_last_op_us;

void sdcard_note_activity(void)
{
    s_last_op_us = esp_timer_get_time();
}

bool sdcard_owner_app(void)
{
    return s_owner_app;
}

esp_err_t sdcard_take(void)
{
    if (!s_storage) {
        return ESP_ERR_NOT_FOUND;
    }
    if (s_owner_app) {
        sdcard_note_activity();
        return ESP_OK;
    }
    esp_err_t err = tinyusb_msc_set_storage_mount_point(
        s_storage, TINYUSB_MSC_STORAGE_MOUNT_APP);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "take: %s", esp_err_to_name(err));
        return err;
    }
    s_owner_app = true;
    sdcard_note_activity();
    ESP_LOGI(TAG, "card taken by firmware (USB sees eject)");
    return ESP_OK;
}

esp_err_t sdcard_release(void)
{
    if (!s_storage) {
        return ESP_ERR_NOT_FOUND;
    }
    if (!s_owner_app) {
        return ESP_OK;
    }
    esp_err_t err = tinyusb_msc_set_storage_mount_point(
        s_storage, TINYUSB_MSC_STORAGE_MOUNT_USB);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "release: %s", esp_err_to_name(err));
        return err;
    }
    s_owner_app = false;
    ESP_LOGI(TAG, "card released to USB");
    return ESP_OK;
}

static void retry_task(void *arg)
{
    (void)arg;
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(DECK_SD_REPROBE_MS));
        if (!s_storage) {
            sdcard_init();
        } else if (s_owner_app &&
                   esp_timer_get_time() - s_last_op_us > DECK_SD_IDLE_RELEASE_US) {
            ESP_LOGI(TAG, "idle timeout, releasing card to USB");
            sdcard_release();
        }
    }
}

void sdcard_start_retry_task(void)
{
    xTaskCreate(retry_task, "sd_retry", 3072, NULL, 3, NULL);
}
