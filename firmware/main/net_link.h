/* Wi-Fi, mDNS discovery, and the framed TCP control channel.
 *
 * One client is served at a time. A new connection displaces the old one, which
 * is what you want when a phone wakes up: the stale socket may take minutes to
 * time out, and the user should not have to wait for it. */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "cJSON.h"

/* Frame types on the wire; see docs/PROTOCOL.md. */
enum {
    DECK_FRAME_JSON  = 0x01,
    DECK_FRAME_INPUT = 0x02,
    DECK_FRAME_PING  = 0x03,
};

void net_link_init(void);

/* True once we hold an IP address in station mode. */
bool net_link_online(void);

/* True when running the provisioning SoftAP instead of joining a network. */
bool net_link_ap_mode(void);

/* Send one JSON frame to the connected client. Takes ownership of `obj`.
 * Silently drops when nobody is connected, which is the right behaviour for
 * fire-and-forget events. */
void net_link_send_json(cJSON *obj);

/* Station credentials, stored in NVS. Setting them reboots into station mode. */
bool net_link_wifi_set(const char *ssid, const char *pass);
void net_link_wifi_forget(void);
bool net_link_has_credentials(void);

/* Blocking scan; caller owns the returned array of {ssid,rssi,auth}. */
cJSON *net_link_scan(void);

/* User-facing device label, surfaced in the mDNS TXT record. */
void net_link_get_name(char *out, size_t len);
bool net_link_set_name(const char *name);

/* Dotted-quad of the current station address, or "0.0.0.0". */
void net_link_ip(char *out, size_t len);
