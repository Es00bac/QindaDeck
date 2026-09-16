#include "action.h"

#include <string.h>

#include "esp_timer.h"
#include "hid_keymap.h"
#include "macro.h"
#include "profiles.h"
#include "usb_hid.h"

/* One auto-repeat slot. A phone can physically hold two pads at once, and a
 * controller can hold two buttons, but repeating both at 16 Hz is not a real
 * workflow and a single slot keeps the release path unambiguous. */
static struct {
    esp_timer_handle_t timer;
    uint8_t mods;
    uint8_t usage;
    uint16_t consumer;
    bool is_consumer;
    bool armed;
} s_repeat;

static void repeat_fire(void *arg)
{
    (void)arg;
    if (!s_repeat.armed) {
        return;
    }
    if (s_repeat.is_consumer) {
        usb_hid_consumer(s_repeat.consumer, true);
        usb_hid_consumer(0, false);
    } else {
        usb_hid_key_down(s_repeat.mods, s_repeat.usage);
        usb_hid_key_up(s_repeat.mods, s_repeat.usage);
    }
}

void action_init(void)
{
    const esp_timer_create_args_t args = {
        .callback = repeat_fire,
        .name = "deck_repeat",
    };
    ESP_ERROR_CHECK(esp_timer_create(&args, &s_repeat.timer));
}

void action_repeat_disarm(void)
{
    if (s_repeat.armed) {
        esp_timer_stop(s_repeat.timer);
        if (s_repeat.is_consumer) {
            usb_hid_consumer(0, false);
        } else {
            usb_hid_key_up(s_repeat.mods, s_repeat.usage);
        }
        s_repeat.armed = false;
    }
}

/* Hold to repeat: a beat of grace, then ~16 Hz, which is what a desktop
 * keyboard does for arrows and volume. */
static void repeat_arm_key(uint8_t mods, uint8_t usage)
{
    s_repeat.mods = mods;
    s_repeat.usage = usage;
    s_repeat.is_consumer = false;
    s_repeat.armed = true;
    esp_timer_stop(s_repeat.timer);
    esp_timer_start_periodic(s_repeat.timer, 62000);
}

static void repeat_arm_consumer(uint16_t usage)
{
    s_repeat.consumer = usage;
    s_repeat.is_consumer = true;
    s_repeat.armed = true;
    esp_timer_stop(s_repeat.timer);
    esp_timer_start_periodic(s_repeat.timer, 62000);
}

static const char *jstr(const cJSON *o, const char *key)
{
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(o, key);
    return cJSON_IsString(v) ? v->valuestring : NULL;
}

static const char *kind_of(const cJSON *act)
{
    const char *k = jstr(act, "kind");
    return k ? k : "noop";
}

bool action_validate(const cJSON *act, char *err, size_t errlen)
{
    if (!cJSON_IsObject(act)) {
        snprintf(err, errlen, "action must be an object");
        return false;
    }
    const char *k = kind_of(act);

    if (!strcmp(k, "noop")) {
        return true;
    }
    if (!strcmp(k, "tap") || !strcmp(k, "hold")) {
        const char *keys = jstr(act, "keys");
        uint8_t mods = 0, usage = 0;
        if (!keys || !hid_keymap_parse(keys, &mods, &usage)) {
            snprintf(err, errlen, "unknown chord '%s'", keys ? keys : "(none)");
            return false;
        }
        return true;
    }
    if (!strcmp(k, "consumer")) {
        const char *media = jstr(act, "media");
        uint16_t usage = 0;
        if (!media || !hid_keymap_media(media, &usage)) {
            snprintf(err, errlen, "unknown media key '%s'", media ? media : "(none)");
            return false;
        }
        return true;
    }
    if (!strcmp(k, "text")) {
        if (!jstr(act, "text")) {
            snprintf(err, errlen, "text action needs text");
            return false;
        }
        return true;
    }
    if (!strcmp(k, "macro")) {
        char merr[180] = {0};
        deck_macro_t *m = macro_compile(cJSON_GetObjectItemCaseSensitive(act, "macro"),
                                       merr, sizeof(merr));
        if (!m) {
            snprintf(err, errlen, "%s", merr[0] ? merr : "invalid macro");
            return false;
        }
        macro_free(m);
        return true;
    }
    if (!strcmp(k, "macro_ref")) {
        if (!jstr(act, "macro_ref")) {
            snprintf(err, errlen, "macro_ref needs a name");
            return false;
        }
        return true;
    }
    if (!strcmp(k, "profile")) {
        if (!jstr(act, "profile")) {
            snprintf(err, errlen, "profile action needs a slug");
            return false;
        }
        return true;
    }
    if (!strcmp(k, "page")) {
        if (!cJSON_IsNumber(cJSON_GetObjectItemCaseSensitive(act, "page"))) {
            snprintf(err, errlen, "page action needs a page number");
            return false;
        }
        return true;
    }
    snprintf(err, errlen, "unknown action kind '%s'", k);
    return false;
}

static bool run_macro_object(const cJSON *m, char *err, size_t errlen)
{
    deck_macro_t *compiled = macro_compile(m, err, errlen);
    if (!compiled) {
        return false;
    }
    if (!macro_submit(compiled)) {
        macro_free(compiled);
        snprintf(err, errlen, "macro engine unavailable");
        return false;
    }
    return true;
}

bool action_fire(const cJSON *act, bool down, bool up, const action_ctx_t *ctx,
                 char *err, size_t errlen)
{
    if (!cJSON_IsObject(act)) {
        snprintf(err, errlen, "action must be an object");
        return false;
    }
    const char *k = kind_of(act);
    bool tap = down && up;
    bool repeatable = cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(act, "repeat_on_hold"));

    /* A release always tears down any armed repeat, whatever the binding now
     * says: otherwise a key could stay down forever after a profile swap. */
    if (up && repeatable) {
        action_repeat_disarm();
    }

    if (!strcmp(k, "noop")) {
        return true;
    }

    if (!strcmp(k, "tap") || !strcmp(k, "hold")) {
        uint8_t mods = 0, usage = 0;
        const char *keys = jstr(act, "keys");
        if (!keys || !hid_keymap_parse(keys, &mods, &usage)) {
            snprintf(err, errlen, "unusable chord");
            return false;
        }
        bool hold_kind = !strcmp(k, "hold");
        if (down) {
            usb_hid_key_down(mods, usage);
            if (repeatable && !tap) {
                repeat_arm_key(mods, usage);
            }
        }
        /* A tap releases immediately. A hold, and a repeat-armed tap, wait for
         * the up edge so the host sees a genuine key-held state. */
        if (up || (tap && !hold_kind && !repeatable)) {
            usb_hid_key_up(mods, usage);
        }
        return true;
    }

    if (!strcmp(k, "consumer")) {
        uint16_t usage = 0;
        const char *media = jstr(act, "media");
        if (!media || !hid_keymap_media(media, &usage)) {
            snprintf(err, errlen, "unknown media key");
            return false;
        }
        if (down) {
            usb_hid_consumer(usage, true);
            if (repeatable && !tap) {
                repeat_arm_consumer(usage);
            }
        }
        if (up || tap) {
            usb_hid_consumer(0, false);
        }
        return true;
    }

    if (!strcmp(k, "text")) {
        if (!down) {
            return true;
        }
        const char *txt = jstr(act, "text");
        cJSON *m = cJSON_CreateObject();
        cJSON_AddStringToObject(m, "name", "text");
        cJSON *steps = cJSON_AddArrayToObject(m, "steps");
        cJSON *st = cJSON_CreateObject();
        cJSON_AddStringToObject(st, "t", "text");
        cJSON_AddStringToObject(st, "s", txt ? txt : "");
        cJSON_AddItemToArray(steps, st);
        bool ok = run_macro_object(m, err, errlen);
        cJSON_Delete(m);
        return ok;
    }

    if (!strcmp(k, "macro")) {
        if (!down) {
            return true;
        }
        return run_macro_object(cJSON_GetObjectItemCaseSensitive(act, "macro"), err, errlen);
    }

    if (!strcmp(k, "macro_ref")) {
        if (!down) {
            return true;
        }
        const char *ref = jstr(act, "macro_ref");
        const cJSON *table = ctx ? ctx->macros : NULL;
        const cJSON *m = (ref && cJSON_IsObject(table))
                             ? cJSON_GetObjectItemCaseSensitive(table, ref)
                             : NULL;
        if (!m) {
            snprintf(err, errlen, "no macro named '%s'", ref ? ref : "(none)");
            return false;
        }
        return run_macro_object(m, err, errlen);
    }

    if (!strcmp(k, "profile")) {
        if (!down) {
            return true;
        }
        const char *slug = jstr(act, "profile");
        if (!slug || !profiles_set_active(slug, 0)) {
            snprintf(err, errlen, "cannot switch to profile '%s'", slug ? slug : "(none)");
            return false;
        }
        return true;
    }

    if (!strcmp(k, "page")) {
        if (!down) {
            return true;
        }
        if (!ctx || !ctx->profile_slug) {
            snprintf(err, errlen, "page switch needs a profile context");
            return false;
        }
        const cJSON *target = cJSON_GetObjectItemCaseSensitive(act, "page");
        if (!profiles_set_active(ctx->profile_slug,
                                 cJSON_IsNumber(target) ? target->valueint : 0)) {
            snprintf(err, errlen, "cannot switch page");
            return false;
        }
        return true;
    }

    snprintf(err, errlen, "unknown action kind '%s'", k);
    return false;
}
