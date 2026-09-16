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

/* ------------------------------------------------------------------ SD card
 *
 * The deck can expose its micro SD slot to the host as a USB mass storage
 * device, composite with the HID. Wiring is board-specific: set these to the
 * slot's CLK/CMD/DAT lines from the board's schematic. DAT1..3 are only used
 * when DECK_SD_WIDTH is 4; with 1-bit mode they may be left as GPIO_NUM_NC.
 *
 * The defaults below are placeholders pending confirmation of the target
 * board's pinout — the SD side simply will not probe until they are right.
 */
#define DECK_SD_PIN_CLK      GPIO_NUM_NC
#define DECK_SD_PIN_CMD      GPIO_NUM_NC
#define DECK_SD_PIN_D0       GPIO_NUM_NC
#define DECK_SD_PIN_D1       GPIO_NUM_NC
#define DECK_SD_PIN_D2       GPIO_NUM_NC
#define DECK_SD_PIN_D3       GPIO_NUM_NC

/* 1 or 4 data lines. 1-bit mode trades speed for two fewer wires and is the
 * safe choice when the board's wiring is unknown; the full-speed USB link is
 * the bottleneck either way. */
#define DECK_SD_WIDTH        1

/* How often to re-probe for a card after a failed init (hot insertion has no
 * detect line on most slots, so polling is the substitute). */
#define DECK_SD_REPROBE_MS   4000
