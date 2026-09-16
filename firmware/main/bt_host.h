/* BLE HID host: pair a gamepad or keyboard to the deck itself.
 *
 * The ESP32-S3 radio is Bluetooth Low Energy only — it has no Bluetooth Classic
 * hardware at all. So BLE HID peripherals (the SteelSeries Stratus+ in its
 * Bluetooth mode, most modern keyboards, 8BitDo pads in BLE mode) can pair,
 * while Classic-only devices (DualShock 4, DualSense, Xbox pads older than the
 * BLE models) cannot, on any firmware. Scans therefore only ever report BLE
 * peripherals advertising the HID service.
 *
 * Pairing is driven from the Android app over the Wi-Fi control channel; the
 * phone is the remote control, and the radio link is the deck's own. */
#pragma once

#include <stdbool.h>
#include <stddef.h>

#include "cJSON.h"

void bt_host_init(void);

/* Blocking BLE scan. Returns an array of {addr, name, rssi, usage, addr_type}.
 * Caller owns the result. */
cJSON *bt_host_scan(int seconds);

/* Connect and bond, then remember the device so it is reconnected on boot.
 * `addr_type` comes from the scan result; -1 reuses a remembered value. */
bool bt_host_pair(const char *addr, int addr_type, const char *name,
                  char *err, size_t errlen);

/* Drop the bond and stop reconnecting. */
bool bt_host_forget(const char *addr);

/* Remembered devices with their live connection state. Caller owns the result. */
cJSON *bt_host_list(void);

/* True while a scan is in progress, so a second one can be refused rather than
 * corrupting the first one's results. */
bool bt_host_scanning(void);
