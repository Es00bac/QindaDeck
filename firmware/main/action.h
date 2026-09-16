/* One executor for "a thing a press can do".
 *
 * Deck buttons and remapped gamepad/keyboard inputs share this vocabulary
 * exactly, so a chord, a macro or a profile switch behaves identically whether
 * it was triggered from the phone's grid or from a physical button on a paired
 * controller. Both callers hand in the same shape of JSON object. */
#pragma once

#include <stdbool.h>
#include <stddef.h>

#include "cJSON.h"

typedef struct {
    /* Profile a `page` action should switch within. NULL for bindings that do
     * not belong to a profile, in which case `page` actions are refused. */
    const char *profile_slug;
    /* Table consulted by `macro_ref` actions; may be NULL. */
    const cJSON *macros;
} action_ctx_t;

void action_init(void);

/* Check an action object without running it. Used at profile/remap upload time
 * so a broken binding is rejected before it can be triggered. */
bool action_validate(const cJSON *act, char *err, size_t errlen);

/* Run an action. `down`/`up` describe the edge: a tap sets both, a hold sets one
 * at a time. Actions that cannot be held (macros, text, page switches) fire on
 * the down edge and ignore the up edge. */
bool action_fire(const cJSON *act, bool down, bool up, const action_ctx_t *ctx,
                 char *err, size_t errlen);

/* Cancel any hold-to-repeat currently armed. */
void action_repeat_disarm(void);
