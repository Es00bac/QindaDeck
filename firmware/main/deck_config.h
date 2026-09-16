/* QindaDeck build-wide tunables. */
#pragma once

#define DECK_FW_VERSION      "1.0.0"

/* TCP control port the phone connects to. */
#define DECK_TCP_PORT        8437

/* mDNS instance/service. Hostname becomes qindadeck.local. */
#define DECK_MDNS_HOSTNAME   "qindadeck"
#define DECK_MDNS_SERVICE    "_qindadeck"
#define DECK_MDNS_PROTO      "_tcp"

/* SoftAP shown when no credentials are stored. */
#define DECK_AP_PREFIX       "QindaDeck-"
#define DECK_AP_PASSWORD     "qindadeck"

/* The macro engine beat. 8 ms => 125 Hz, matching the USB polling interval we
 * request in the HID descriptor. Every interpolated motion report lands on one
 * of these ticks. */
#define DECK_TICK_MS         8

/* Absolute pointer axes are reported over this range on both axes. */
#define DECK_ABS_MAX         32767

/* Guard rails so a malformed profile cannot wedge the device. */
#define DECK_MAX_STEPS       2048
#define DECK_MAX_NEST        4
#define DECK_MAX_FRAME       65536
#define DECK_MAX_PROFILES    32

/* SPIFFS mount point holding profile JSON. */
#define DECK_FS_BASE         "/deck"
