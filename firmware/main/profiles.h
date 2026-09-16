/* Profile storage and button dispatch.
 *
 * One JSON file per profile under SPIFFS, so a profile can be replaced without
 * rewriting the others and a corrupt upload cannot take the whole set down. The
 * active profile and page live in NVS, which survives a power cut. */
#pragma once

#include <stdbool.h>
#include <stddef.h>

#include "cJSON.h"

void profiles_init(void);

/* Summaries only (slug, name, icon, accent, page count) — the deck list screen
 * does not need every button. Caller owns the result. */
cJSON *profiles_list(void);

/* Full profile, or NULL if unknown. Caller owns the result. */
cJSON *profiles_get(const char *slug);

/* Validate and store. Rejects anything the firmware could not later execute, so
 * a broken macro is caught at upload time rather than mid-drag. */
bool profiles_put(const cJSON *profile, char *err, size_t errlen);

bool profiles_del(const char *slug);
int profiles_count(void);

bool profiles_set_active(const char *slug, int page);
void profiles_get_active(char *slug, size_t len, int *page);

/* Restore the built-in set, discarding user edits. */
void profiles_factory_reset(void);

/* Fire a button. `state` is "down", "up" or "tap"; holds and auto-repeat need
 * the down/up pair, everything else acts on "tap" (or on "down" alone).
 * `slug` and `page` may be NULL/-1 to mean "whatever is active". */
bool profiles_press(const char *slug, int page, int index, const char *state,
                    char *err, size_t errlen);
