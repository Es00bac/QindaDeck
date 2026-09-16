/* Remapping physical inputs from a paired Bluetooth device.
 *
 * A gamepad's report layout is whatever its vendor decided, and parsing an
 * arbitrary HID report descriptor well enough to name "the A button" is a losing
 * game. So bindings are expressed as predicates over the raw input report
 * instead, and the app learns the right predicate by watching you press the
 * button:
 *
 *   {"kind":"btn",  "byte":4, "bit":0}                 a bit going high
 *   {"kind":"axis", "byte":2, "dir":"lo", "thr":64}    a byte crossing a threshold
 *   {"kind":"key",  "usage":4}                          a keyboard usage being held
 *
 * That works for any device without knowing anything about it in advance. Each
 * binding carries an action in the same vocabulary as a deck button, so a
 * controller button can fire a chord, type text, or run a full mouse macro. */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "cJSON.h"

void remap_init(void);

/* Stored remap document for one device, or NULL. Caller owns the result. */
cJSON *remap_get(const char *addr);

/* Validate and store. Rejects unusable bindings at upload time. */
bool remap_put(const cJSON *doc, char *err, size_t errlen);
bool remap_del(const char *addr);

/* Summaries of every stored remap: address, name, binding count. */
cJSON *remap_list(void);

/* Feed one input report from a paired device. `usage` is the esp_hid usage class
 * so keyboard reports can also be parsed as keys and passed through. */
void remap_feed(const char *addr, int usage, uint16_t report_id,
                const uint8_t *data, uint16_t len);

/* Learn mode: while on, every input report is summarised to the phone as a
 * `src_input` event so the editor can offer the predicate that just changed.
 * Bindings still fire, unless `mute` is set — which is what the editor wants, so
 * pressing a button to bind it does not also trigger its old action. */
void remap_set_learn(bool on, bool mute);
bool remap_learn_active(void);

/* Forget edge state for a device, so a reconnect does not synthesise releases
 * against a report that no longer exists. */
void remap_reset_device(const char *addr);
