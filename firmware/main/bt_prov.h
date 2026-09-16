/* Wi-Fi provisioning over BLE.
 *
 * A short, manually armed window: the deck advertises a tiny GATT service,
 * the phone writes SSID + password + apply, and the deck stores them and
 * reboots onto the network. The rest of the time the radio stays what it
 * always was — the BLE HID *host* for controllers. The window is explicit
 * (`bt_prov` op) or automatic while the deck sits in SoftAP with no
 * credentials, so a fresh deck is provisionable straight from the app
 * without joining its access point. */
#pragma once

#include <stdbool.h>

/* Register the GATTS service once. Called after bt_host_init — the Bluedroid
 * stack already exists; GATTS and the HID host's GATTC run side by side. */
void bt_prov_init(void);

/* Open the window: connectable advertising carrying the provisioning service
 * UUID. seconds == 0 means "until reboot". Re-arming an open window just
 * extends it. */
void bt_prov_start(int seconds);

/* Close the window early and forget any staged credentials. */
void bt_prov_stop(void);

bool bt_prov_active(void);
