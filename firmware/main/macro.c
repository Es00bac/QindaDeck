#include "macro.h"

#include <stdlib.h>
#include <string.h>

#include "deck_config.h"
#include "displays.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "hid_keymap.h"
#include "motion.h"
#include "usb_hid.h"

static const char *TAG = "macro";

typedef enum {
    ST_KEY_TAP = 0,
    ST_KEY_DOWN,
    ST_KEY_UP,
    ST_TEXT,
    ST_CONSUMER,
    ST_MOVE_ABS,
    ST_MOVE_REL,
    ST_MDOWN,
    ST_MUP,
    ST_CLICK,
    ST_SCROLL,
    ST_DELAY,
    ST_REPEAT_BEGIN,
    ST_REPEAT_END,
} step_kind_t;

typedef struct {
    uint8_t kind;
    uint8_t mods;
    uint8_t usage;
    uint8_t btn;
    uint8_t ease;
    uint8_t jitter;
    uint16_t consumer;
    int16_t x, y;          /* abs target (display px) or relative delta */
    int16_t wheel, hwheel;
    uint16_t dur_ms;
    uint16_t after_ms;
    uint16_t count;        /* click count, or repeat iterations */
    uint16_t gap_ms;
    int32_t pair;          /* REPEAT_BEGIN -> index after its END, and back */
    char *text;            /* ST_TEXT owns this */
    char *display;         /* ST_MOVE_ABS may own this */
} step_t;

struct deck_macro {
    char name[48];
    uint16_t loop;
    int count;
    int cap;
    step_t *steps;
};

/* ------------------------------------------------------------------ compile */

static bool grow(deck_macro_t *m, char *err, size_t errlen)
{
    if (m->count < m->cap) {
        return true;
    }
    int cap = m->cap ? m->cap * 2 : 16;
    if (cap > DECK_MAX_STEPS) {
        cap = DECK_MAX_STEPS;
    }
    if (m->count >= cap) {
        snprintf(err, errlen, "macro exceeds %d steps", DECK_MAX_STEPS);
        return false;
    }
    step_t *n = realloc(m->steps, sizeof(step_t) * (size_t)cap);
    if (!n) {
        snprintf(err, errlen, "out of memory compiling macro");
        return false;
    }
    memset(n + m->cap, 0, sizeof(step_t) * (size_t)(cap - m->cap));
    m->steps = n;
    m->cap = cap;
    return true;
}

static step_t *emit(deck_macro_t *m, step_kind_t kind, char *err, size_t errlen)
{
    if (!grow(m, err, errlen)) {
        return NULL;
    }
    step_t *s = &m->steps[m->count++];
    memset(s, 0, sizeof(*s));
    s->kind = (uint8_t)kind;
    s->ease = DECK_EASE_IN_OUT;
    return s;
}

static int jnum(const cJSON *o, const char *key, int dflt)
{
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(o, key);
    return cJSON_IsNumber(v) ? v->valueint : dflt;
}

static const char *jstr(const cJSON *o, const char *key)
{
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(o, key);
    return cJSON_IsString(v) ? v->valuestring : NULL;
}

static int clampi(int v, int lo, int hi)
{
    return v < lo ? lo : (v > hi ? hi : v);
}

static void apply_motion_opts(step_t *s, const cJSON *o)
{
    s->dur_ms = (uint16_t)clampi(jnum(o, "dur", 0), 0, 60000);
    s->ease = (uint8_t)motion_ease_from_name(jstr(o, "ease"));
    s->jitter = (uint8_t)clampi(jnum(o, "jitter", 0), 0, 32);
}

static bool compile_steps(deck_macro_t *m, const cJSON *arr, int depth,
                         char *err, size_t errlen);

/* `drag` is sugar. Expanding it here keeps the interpreter free of a composite
 * step that would need its own mid-step abort handling. */
static bool compile_drag(deck_macro_t *m, const cJSON *o, char *err, size_t errlen)
{
    uint8_t btn = DECK_BTN_LEFT;
    const char *bn = jstr(o, "btn");
    if (bn && !hid_keymap_button(bn, &btn)) {
        snprintf(err, errlen, "unknown mouse button '%s'", bn);
        return false;
    }
    const char *disp = jstr(o, "display");
    const cJSON *to = cJSON_GetObjectItemCaseSensitive(o, "to");
    if (!cJSON_IsObject(to)) {
        snprintf(err, errlen, "drag needs a 'to' point");
        return false;
    }
    const cJSON *from = cJSON_GetObjectItemCaseSensitive(o, "from");

    /* Optional approach move: only when the author pinned a start point. */
    if (cJSON_IsObject(from)) {
        step_t *s = emit(m, ST_MOVE_ABS, err, errlen);
        if (!s) return false;
        s->x = (int16_t)clampi(jnum(from, "x", 0), -32767, 32767);
        s->y = (int16_t)clampi(jnum(from, "y", 0), -32767, 32767);
        /* Travel to the grab point at a fixed brisk pace rather than instantly:
         * some UIs only arm a drag handle once the pointer has hovered it. */
        s->dur_ms = (uint16_t)clampi(jnum(o, "approach", 200), 0, 60000);
        s->ease = DECK_EASE_OUT;
        if (disp) s->display = strdup(disp);
    }

    step_t *d = emit(m, ST_MDOWN, err, errlen);
    if (!d) return false;
    d->btn = btn;
    /* A press needs to settle before motion or the host may treat the whole
     * thing as a click. */
    d->after_ms = (uint16_t)clampi(jnum(o, "grip", 40), 0, 5000);

    step_t *mv = emit(m, ST_MOVE_ABS, err, errlen);
    if (!mv) return false;
    mv->x = (int16_t)clampi(jnum(to, "x", 0), -32767, 32767);
    mv->y = (int16_t)clampi(jnum(to, "y", 0), -32767, 32767);
    apply_motion_opts(mv, o);
    if (mv->dur_ms == 0) {
        mv->dur_ms = 400;   /* a zero-duration drag is almost never intended */
    }
    if (disp) mv->display = strdup(disp);

    step_t *u = emit(m, ST_MUP, err, errlen);
    if (!u) return false;
    u->btn = btn;
    u->after_ms = (uint16_t)clampi(jnum(o, "after", 0), 0, 60000);
    return true;
}

static bool compile_one(deck_macro_t *m, const cJSON *o, int depth,
                       char *err, size_t errlen)
{
    const char *t = jstr(o, "t");
    if (!t) {
        snprintf(err, errlen, "step is missing 't'");
        return false;
    }

    if (!strcmp(t, "drag")) {
        return compile_drag(m, o, err, errlen);
    }

    if (!strcmp(t, "repeat")) {
        if (depth >= DECK_MAX_NEST) {
            snprintf(err, errlen, "repeat nested deeper than %d", DECK_MAX_NEST);
            return false;
        }
        const cJSON *inner = cJSON_GetObjectItemCaseSensitive(o, "steps");
        if (!cJSON_IsArray(inner)) {
            snprintf(err, errlen, "repeat needs a 'steps' array");
            return false;
        }
        int count = clampi(jnum(o, "count", 1), 1, 1000);
        int begin = m->count;
        step_t *b = emit(m, ST_REPEAT_BEGIN, err, errlen);
        if (!b) return false;
        b->count = (uint16_t)count;
        if (!compile_steps(m, inner, depth + 1, err, errlen)) {
            return false;
        }
        step_t *e = emit(m, ST_REPEAT_END, err, errlen);
        if (!e) return false;
        e->pair = begin;
        /* Re-read: emit() may have reallocated the array. */
        m->steps[begin].pair = m->count;
        return true;
    }

    step_kind_t kind;
    if (!strcmp(t, "tap")) kind = ST_KEY_TAP;
    else if (!strcmp(t, "down")) kind = ST_KEY_DOWN;
    else if (!strcmp(t, "up")) kind = ST_KEY_UP;
    else if (!strcmp(t, "text")) kind = ST_TEXT;
    else if (!strcmp(t, "consumer")) kind = ST_CONSUMER;
    else if (!strcmp(t, "move_abs")) kind = ST_MOVE_ABS;
    else if (!strcmp(t, "move_rel")) kind = ST_MOVE_REL;
    else if (!strcmp(t, "mdown")) kind = ST_MDOWN;
    else if (!strcmp(t, "mup")) kind = ST_MUP;
    else if (!strcmp(t, "click")) kind = ST_CLICK;
    else if (!strcmp(t, "scroll")) kind = ST_SCROLL;
    else if (!strcmp(t, "delay")) kind = ST_DELAY;
    else {
        snprintf(err, errlen, "unknown step type '%s'", t);
        return false;
    }

    step_t *s = emit(m, kind, err, errlen);
    if (!s) return false;
    s->after_ms = (uint16_t)clampi(jnum(o, "after", 0), 0, 60000);

    switch (kind) {
    case ST_KEY_TAP:
    case ST_KEY_DOWN:
    case ST_KEY_UP: {
        const char *keys = jstr(o, "keys");
        if (!keys || !hid_keymap_parse(keys, &s->mods, &s->usage)) {
            snprintf(err, errlen, "unknown key chord '%s'", keys ? keys : "(none)");
            return false;
        }
        /* A tap wants a dwell the host will actually sample. */
        s->gap_ms = (uint16_t)clampi(jnum(o, "hold", 12), 1, 5000);
        break;
    }
    case ST_TEXT: {
        const char *str = jstr(o, "s");
        if (!str) {
            snprintf(err, errlen, "text step needs 's'");
            return false;
        }
        if (strlen(str) > 512) {
            snprintf(err, errlen, "text step longer than 512 bytes");
            return false;
        }
        for (const char *p = str; *p; p++) {
            uint8_t mo = 0, us = 0;
            if (!hid_keymap_ascii(*p, &mo, &us)) {
                snprintf(err, errlen, "cannot type character 0x%02x", (unsigned char)*p);
                return false;
            }
        }
        s->text = strdup(str);
        if (!s->text) {
            snprintf(err, errlen, "out of memory");
            return false;
        }
        s->gap_ms = (uint16_t)clampi(jnum(o, "cps_gap", DECK_TICK_MS), 1, 1000);
        break;
    }
    case ST_CONSUMER: {
        const char *media = jstr(o, "media");
        if (!media || !hid_keymap_media(media, &s->consumer)) {
            snprintf(err, errlen, "unknown media key '%s'", media ? media : "(none)");
            return false;
        }
        s->gap_ms = (uint16_t)clampi(jnum(o, "hold", 40), 1, 5000);
        break;
    }
    case ST_MOVE_ABS: {
        const cJSON *x = cJSON_GetObjectItemCaseSensitive(o, "x");
        const cJSON *y = cJSON_GetObjectItemCaseSensitive(o, "y");
        if (!cJSON_IsNumber(x) || !cJSON_IsNumber(y)) {
            snprintf(err, errlen, "move_abs needs x and y");
            return false;
        }
        s->x = (int16_t)clampi(x->valueint, -32767, 32767);
        s->y = (int16_t)clampi(y->valueint, -32767, 32767);
        apply_motion_opts(s, o);
        const char *disp = jstr(o, "display");
        if (disp) {
            s->display = strdup(disp);
        }
        break;
    }
    case ST_MOVE_REL: {
        const cJSON *dx = cJSON_GetObjectItemCaseSensitive(o, "dx");
        const cJSON *dy = cJSON_GetObjectItemCaseSensitive(o, "dy");
        if (!cJSON_IsNumber(dx) || !cJSON_IsNumber(dy)) {
            snprintf(err, errlen, "move_rel needs dx and dy");
            return false;
        }
        s->x = (int16_t)clampi(dx->valueint, -32767, 32767);
        s->y = (int16_t)clampi(dy->valueint, -32767, 32767);
        apply_motion_opts(s, o);
        break;
    }
    case ST_MDOWN:
    case ST_MUP:
    case ST_CLICK: {
        const char *bn = jstr(o, "btn");
        s->btn = DECK_BTN_LEFT;
        if (bn && !hid_keymap_button(bn, &s->btn)) {
            snprintf(err, errlen, "unknown mouse button '%s'", bn);
            return false;
        }
        s->count = (uint16_t)clampi(jnum(o, "count", 1), 1, 16);
        s->gap_ms = (uint16_t)clampi(jnum(o, "gap", 60), 1, 5000);
        break;
    }
    case ST_SCROLL: {
        s->wheel = (int16_t)clampi(jnum(o, "amount", 0), -1000, 1000);
        s->hwheel = (int16_t)clampi(jnum(o, "hamount", 0), -1000, 1000);
        s->dur_ms = (uint16_t)clampi(jnum(o, "dur", 0), 0, 60000);
        if (s->wheel == 0 && s->hwheel == 0) {
            snprintf(err, errlen, "scroll step scrolls nothing");
            return false;
        }
        break;
    }
    case ST_DELAY: {
        const cJSON *ms = cJSON_GetObjectItemCaseSensitive(o, "ms");
        if (!cJSON_IsNumber(ms)) {
            snprintf(err, errlen, "delay needs ms");
            return false;
        }
        s->dur_ms = (uint16_t)clampi(ms->valueint, 0, 60000);
        break;
    }
    default:
        break;
    }
    return true;
}

static bool compile_steps(deck_macro_t *m, const cJSON *arr, int depth,
                         char *err, size_t errlen)
{
    int n = cJSON_GetArraySize(arr);
    for (int i = 0; i < n; i++) {
        const cJSON *o = cJSON_GetArrayItem(arr, i);
        if (!cJSON_IsObject(o)) {
            snprintf(err, errlen, "step %d is not an object", i);
            return false;
        }
        if (!compile_one(m, o, depth, err, errlen)) {
            /* Prefix the failing index so the editor can highlight the step. */
            char tmp[160];
            snprintf(tmp, sizeof(tmp), "step %d: %s", i, err);
            snprintf(err, errlen, "%s", tmp);
            return false;
        }
    }
    return true;
}

deck_macro_t *macro_compile(const cJSON *obj, char *err, size_t errlen)
{
    if (!cJSON_IsObject(obj)) {
        snprintf(err, errlen, "macro must be an object");
        return NULL;
    }
    const cJSON *arr = cJSON_GetObjectItemCaseSensitive(obj, "steps");
    if (!cJSON_IsArray(arr)) {
        snprintf(err, errlen, "macro needs a 'steps' array");
        return NULL;
    }

    deck_macro_t *m = calloc(1, sizeof(*m));
    if (!m) {
        snprintf(err, errlen, "out of memory");
        return NULL;
    }
    const char *nm = jstr(obj, "name");
    snprintf(m->name, sizeof(m->name), "%s", nm ? nm : "macro");
    m->loop = (uint16_t)clampi(jnum(obj, "loop", 1), 1, 1000);

    if (!compile_steps(m, arr, 0, err, errlen)) {
        macro_free(m);
        return NULL;
    }
    if (m->count == 0) {
        snprintf(err, errlen, "macro has no steps");
        macro_free(m);
        return NULL;
    }
    return m;
}

void macro_free(deck_macro_t *m)
{
    if (!m) {
        return;
    }
    for (int i = 0; i < m->count; i++) {
        free(m->steps[i].text);
        free(m->steps[i].display);
    }
    free(m->steps);
    free(m);
}

const char *macro_name(const deck_macro_t *m)
{
    return m ? m->name : NULL;
}

int macro_step_count(const deck_macro_t *m)
{
    return m ? m->count : 0;
}

/* ------------------------------------------------------------------ engine */

static TaskHandle_t s_task;
static SemaphoreHandle_t s_lock;
static deck_macro_t *s_pending;
static deck_macro_t *s_current;
static volatile bool s_abort;
static macro_event_cb_t s_event_cb;

void macro_set_event_cb(macro_event_cb_t cb)
{
    s_event_cb = cb;
}

static void fire(const char *state, const char *name, const char *reason)
{
    macro_event_cb_t cb = s_event_cb;
    if (cb) {
        cb(state, name ? name : "", reason);
    }
}

/* Sleep in tick-sized slices so an abort is noticed promptly even during a long
 * delay. Returns false if the macro was aborted mid-wait. */
static bool nap(uint32_t ms)
{
    while (ms > 0) {
        if (s_abort) {
            return false;
        }
        uint32_t slice = ms > DECK_TICK_MS ? DECK_TICK_MS : ms;
        vTaskDelay(pdMS_TO_TICKS(slice));
        ms -= slice;
    }
    return !s_abort;
}

static bool run_move_abs(const step_t *s)
{
    uint16_t tx, ty;
    displays_to_abs(s->display, s->x, s->y, &tx, &ty);

    /* Without a previously reported position there is nothing to interpolate
     * from: the host's cursor could be anywhere. Land on the target directly and
     * interpolate from there onwards. */
    if (!usb_hid_have_abs() || s->dur_ms == 0) {
        usb_hid_mouse_abs(tx, ty, 0);
        return !s_abort;
    }

    uint16_t sx, sy;
    usb_hid_last_abs(&sx, &sy);
    int ticks = (s->dur_ms + DECK_TICK_MS - 1) / DECK_TICK_MS;
    if (ticks < 1) {
        ticks = 1;
    }

    TickType_t last = xTaskGetTickCount();
    for (int i = 1; i <= ticks; i++) {
        if (s_abort) {
            return false;
        }
        float lin = (float)i / (float)ticks;
        float p = motion_ease((deck_ease_t)s->ease, lin);
        int nx = (int)sx + (int)((float)((int)tx - (int)sx) * p);
        int ny = (int)sy + (int)((float)((int)ty - (int)sy) * p);
        if (s->jitter && i < ticks) {
            float env = motion_jitter_envelope(lin);
            nx += (int)((float)motion_jitter(s->jitter) * env);
            ny += (int)((float)motion_jitter(s->jitter) * env);
        }
        if (nx < 0) nx = 0;
        if (ny < 0) ny = 0;
        if (nx > DECK_ABS_MAX) nx = DECK_ABS_MAX;
        if (ny > DECK_ABS_MAX) ny = DECK_ABS_MAX;
        usb_hid_mouse_abs((uint16_t)nx, (uint16_t)ny, 0);
        vTaskDelayUntil(&last, pdMS_TO_TICKS(DECK_TICK_MS));
    }
    /* Always finish exactly on target, whatever the jitter did. */
    usb_hid_mouse_abs(tx, ty, 0);
    return !s_abort;
}

static bool run_move_rel(const step_t *s)
{
    if (s->dur_ms == 0) {
        /* Still chunked, because one report cannot carry more than +/-127. */
        int remx = s->x, remy = s->y;
        while ((remx || remy) && !s_abort) {
            int dx = clampi(remx, -127, 127);
            int dy = clampi(remy, -127, 127);
            usb_hid_mouse_rel(dx, dy, 0, 0);
            remx -= dx;
            remy -= dy;
            if (remx || remy) {
                vTaskDelay(pdMS_TO_TICKS(1));
            }
        }
        return !s_abort;
    }

    int ticks = (s->dur_ms + DECK_TICK_MS - 1) / DECK_TICK_MS;
    if (ticks < 1) {
        ticks = 1;
    }
    /* Track the ideal eased position and emit the difference, so rounding never
     * accumulates into a drift away from the requested delta. */
    int sent_x = 0, sent_y = 0;
    TickType_t last = xTaskGetTickCount();
    for (int i = 1; i <= ticks; i++) {
        if (s_abort) {
            return false;
        }
        float lin = (float)i / (float)ticks;
        float p = motion_ease((deck_ease_t)s->ease, lin);
        int want_x = (int)((float)s->x * p);
        int want_y = (int)((float)s->y * p);
        int dx = want_x - sent_x;
        int dy = want_y - sent_y;
        if (s->jitter && i < ticks) {
            float env = motion_jitter_envelope(lin);
            dx += (int)((float)motion_jitter(s->jitter) * env);
            dy += (int)((float)motion_jitter(s->jitter) * env);
        }
        if (dx || dy) {
            int cdx = clampi(dx, -127, 127);
            int cdy = clampi(dy, -127, 127);
            usb_hid_mouse_rel(cdx, cdy, 0, 0);
            sent_x += cdx;
            sent_y += cdy;
        }
        vTaskDelayUntil(&last, pdMS_TO_TICKS(DECK_TICK_MS));
    }
    /* Settle any residue left by clamping or jitter. */
    int fixx = s->x - sent_x, fixy = s->y - sent_y;
    while ((fixx || fixy) && !s_abort) {
        int dx = clampi(fixx, -127, 127);
        int dy = clampi(fixy, -127, 127);
        usb_hid_mouse_rel(dx, dy, 0, 0);
        fixx -= dx;
        fixy -= dy;
        if (fixx || fixy) {
            vTaskDelay(pdMS_TO_TICKS(1));
        }
    }
    return !s_abort;
}

static bool run_scroll(const step_t *s)
{
    int total_v = s->wheel, total_h = s->hwheel;
    if (s->dur_ms == 0) {
        int remv = total_v, remh = total_h;
        while ((remv || remh) && !s_abort) {
            int v = clampi(remv, -127, 127);
            int h = clampi(remh, -127, 127);
            usb_hid_mouse_rel(0, 0, v, h);
            remv -= v;
            remh -= h;
            if (remv || remh) {
                vTaskDelay(pdMS_TO_TICKS(1));
            }
        }
        return !s_abort;
    }

    int ticks = (s->dur_ms + DECK_TICK_MS - 1) / DECK_TICK_MS;
    if (ticks < 1) ticks = 1;
    int sent_v = 0, sent_h = 0;
    TickType_t last = xTaskGetTickCount();
    for (int i = 1; i <= ticks; i++) {
        if (s_abort) {
            return false;
        }
        int want_v = total_v * i / ticks;
        int want_h = total_h * i / ticks;
        int v = clampi(want_v - sent_v, -127, 127);
        int h = clampi(want_h - sent_h, -127, 127);
        if (v || h) {
            usb_hid_mouse_rel(0, 0, v, h);
            sent_v += v;
            sent_h += h;
        }
        vTaskDelayUntil(&last, pdMS_TO_TICKS(DECK_TICK_MS));
    }
    return !s_abort;
}

static bool run_text(const step_t *s)
{
    for (const char *p = s->text; *p; p++) {
        if (s_abort) {
            return false;
        }
        uint8_t mods = 0, usage = 0;
        if (!hid_keymap_ascii(*p, &mods, &usage)) {
            continue;
        }
        usb_hid_key_down(mods, usage);
        if (!nap(s->gap_ms)) {
            usb_hid_key_up(mods, usage);
            return false;
        }
        usb_hid_key_up(mods, usage);
        if (!nap(s->gap_ms)) {
            return false;
        }
    }
    return true;
}

static bool run_step(const step_t *s)
{
    switch (s->kind) {
    case ST_KEY_TAP:
        usb_hid_key_down(s->mods, s->usage);
        if (!nap(s->gap_ms)) {
            usb_hid_key_up(s->mods, s->usage);
            return false;
        }
        usb_hid_key_up(s->mods, s->usage);
        break;
    case ST_KEY_DOWN:
        usb_hid_key_down(s->mods, s->usage);
        break;
    case ST_KEY_UP:
        usb_hid_key_up(s->mods, s->usage);
        break;
    case ST_TEXT:
        if (!run_text(s)) return false;
        break;
    case ST_CONSUMER:
        usb_hid_consumer(s->consumer, true);
        if (!nap(s->gap_ms)) {
            usb_hid_consumer(0, false);
            return false;
        }
        usb_hid_consumer(0, false);
        break;
    case ST_MOVE_ABS:
        if (!run_move_abs(s)) return false;
        break;
    case ST_MOVE_REL:
        if (!run_move_rel(s)) return false;
        break;
    case ST_MDOWN:
        usb_hid_mouse_buttons(s->btn, true);
        break;
    case ST_MUP:
        usb_hid_mouse_buttons(s->btn, false);
        break;
    case ST_CLICK:
        for (int i = 0; i < s->count; i++) {
            usb_hid_mouse_buttons(s->btn, true);
            if (!nap(s->gap_ms / 2 ? s->gap_ms / 2 : 1)) {
                usb_hid_mouse_buttons(s->btn, false);
                return false;
            }
            usb_hid_mouse_buttons(s->btn, false);
            if (i + 1 < s->count && !nap(s->gap_ms)) {
                return false;
            }
        }
        break;
    case ST_SCROLL:
        if (!run_scroll(s)) return false;
        break;
    case ST_DELAY:
        if (!nap(s->dur_ms)) return false;
        break;
    default:
        break;
    }
    return s->after_ms ? nap(s->after_ms) : !s_abort;
}

static void run_macro(deck_macro_t *m)
{
    fire("start", m->name, NULL);

    /* One counter per nesting level, indexed by the REPEAT_BEGIN's position in
     * the stack rather than in the step array. */
    int stack[DECK_MAX_NEST];
    int sp;

    bool aborted = false;
    for (int pass = 0; pass < m->loop && !aborted; pass++) {
        sp = 0;
        for (int pc = 0; pc < m->count; pc++) {
            const step_t *s = &m->steps[pc];
            if (s->kind == ST_REPEAT_BEGIN) {
                if (sp < DECK_MAX_NEST) {
                    stack[sp++] = s->count;
                } else {
                    pc = s->pair - 1;   /* should not happen; skip the block */
                }
                continue;
            }
            if (s->kind == ST_REPEAT_END) {
                if (sp > 0 && --stack[sp - 1] > 0) {
                    pc = s->pair;       /* jump back onto the BEGIN */
                } else if (sp > 0) {
                    sp--;
                }
                continue;
            }
            if (!run_step(s)) {
                aborted = true;
                break;
            }
        }
    }

    /* Whatever happened, the device must not be left holding anything. */
    usb_hid_release_everything();
    fire(aborted ? "abort" : "done", m->name, aborted ? "stopped" : NULL);
}

static void macro_task(void *arg)
{
    (void)arg;
    for (;;) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);

        xSemaphoreTake(s_lock, portMAX_DELAY);
        deck_macro_t *m = s_pending;
        s_pending = NULL;
        s_current = m;
        s_abort = false;
        xSemaphoreGive(s_lock);

        if (!m) {
            continue;
        }
        ESP_LOGI(TAG, "run '%s' (%d steps, loop %u)", m->name, m->count, m->loop);
        run_macro(m);

        xSemaphoreTake(s_lock, portMAX_DELAY);
        s_current = NULL;
        xSemaphoreGive(s_lock);
        macro_free(m);
    }
}

void macro_engine_init(void)
{
    s_lock = xSemaphoreCreateMutex();
    /* 6 KB: the interpreter itself is shallow, but cJSON-free step execution
     * still calls into TinyUSB. */
    xTaskCreate(macro_task, "deck_macro", 6144, NULL, 6, &s_task);
}

bool macro_submit(deck_macro_t *m)
{
    if (!m || !s_task) {
        return false;
    }
    /* Pre-empt: a new press should interrupt the old macro, not queue behind it.
     * Wait briefly for the engine to notice, so the two never overlap. */
    macro_stop();
    for (int i = 0; i < 100; i++) {
        xSemaphoreTake(s_lock, portMAX_DELAY);
        bool busy = (s_current != NULL);
        xSemaphoreGive(s_lock);
        if (!busy) {
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(2));
    }

    xSemaphoreTake(s_lock, portMAX_DELAY);
    deck_macro_t *stale = s_pending;
    s_pending = m;
    xSemaphoreGive(s_lock);
    macro_free(stale);

    xTaskNotifyGive(s_task);
    return true;
}

void macro_stop(void)
{
    s_abort = true;
}

const char *macro_running(void)
{
    const char *name = NULL;
    if (!s_lock) {
        return NULL;
    }
    xSemaphoreTake(s_lock, portMAX_DELAY);
    if (s_current) {
        name = s_current->name;
    }
    xSemaphoreGive(s_lock);
    return name;
}
