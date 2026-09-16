/* The micro SD slot, exposed to the host as USB mass storage.
 *
 * The deck does not use the card itself: it exists purely so the host (BIOS,
 * bootloader, OS) sees a plain USB drive next to the keyboard and mouse. That
 * is what makes booting a live image written to the card possible while the
 * deck's keyboard still works in the boot menu. */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

/* Probe the slot and, if a card is found, register it as the MSC LUN.
 * Returns ESP_ERR_NOT_FOUND when no card answers. Safe to call again after a
 * failure — the retry task does exactly that for cards inserted late. */
esp_err_t sdcard_init(void);

/* Background re-probe for cards inserted after boot. Slots have no detect
 * line, so polling is the hot-swap mechanism. */
void sdcard_start_retry_task(void);

/* Take the card from the USB host for firmware-side file I/O. The host sees
 * its drive eject; sdcard_release() hands it back. While taken, the FAT
 * filesystem is mounted at /sdcard. */
esp_err_t sdcard_take(void);
esp_err_t sdcard_release(void);

/* True while the firmware, not the USB host, owns the card. */
bool sdcard_owner_app(void);

/* Reset the idle clock. File ops call this; a minute of silence releases the
 * card back to USB automatically so a forgotten session cannot strand the
 * host's drive. */
void sdcard_note_activity(void);

/* True once a card is live as the USB LUN. */
bool sdcard_present(void);

/* Card capacity in bytes, 0 when absent. */
uint64_t sdcard_capacity_bytes(void);
