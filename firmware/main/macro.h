/* The macro engine.
 *
 * JSON arrives from the phone, is compiled once into a flat step array, and then
 * interpreted on a dedicated task that beats every DECK_TICK_MS. Motion steps
 * are spread across that beat, which is what turns "go to 1400,700 over 600 ms"
 * into ~75 interpolated pointer reports instead of one teleport. */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "cJSON.h"

typedef struct deck_macro deck_macro_t;

/* Compile a `{"name":…,"loop":…,"steps":[…]}` object. Returns NULL and fills
 * `err` on a malformed macro, so a bad profile is rejected at upload time rather
 * than halfway through a drag. */
deck_macro_t *macro_compile(const cJSON *obj, char *err, size_t errlen);
void macro_free(deck_macro_t *m);
const char *macro_name(const deck_macro_t *m);
int macro_step_count(const deck_macro_t *m);

/* Called once at boot; starts the engine task. */
void macro_engine_init(void);

/* Hand a compiled macro to the engine, which takes ownership and frees it when
 * done. Anything already running is aborted first, with every key and mouse
 * button released, so macros can never overlap or leave state behind. */
bool macro_submit(deck_macro_t *m);

/* Abort the running macro and release everything. Safe to call when idle. */
void macro_stop(void);

/* Name of the running macro, or NULL when idle. */
const char *macro_running(void);

/* Engine lifecycle notifications, used to push events to the phone. `state` is
 * "start", "done" or "abort". */
typedef void (*macro_event_cb_t)(const char *state, const char *name, const char *reason);
void macro_set_event_cb(macro_event_cb_t cb);
