#include "motion.h"

#include <string.h>

#include "esp_random.h"

deck_ease_t motion_ease_from_name(const char *name)
{
    if (!name) return DECK_EASE_IN_OUT;
    if (!strcasecmp(name, "linear")) return DECK_EASE_LINEAR;
    if (!strcasecmp(name, "in")) return DECK_EASE_IN;
    if (!strcasecmp(name, "out")) return DECK_EASE_OUT;
    return DECK_EASE_IN_OUT;
}

float motion_ease(deck_ease_t e, float t)
{
    if (t <= 0.0f) return 0.0f;
    if (t >= 1.0f) return 1.0f;

    switch (e) {
    case DECK_EASE_LINEAR:
        return t;
    case DECK_EASE_IN:
        return t * t;
    case DECK_EASE_OUT:
        return t * (2.0f - t);
    case DECK_EASE_IN_OUT:
    default:
        /* Cubic in-out: slow departure, quick middle, soft landing. */
        return (t < 0.5f) ? (4.0f * t * t * t)
                          : (1.0f - 2.0f * (1.0f - t) * (1.0f - t) * (1.0f - t));
    }
}

int motion_jitter(int span)
{
    if (span <= 0) {
        return 0;
    }
    uint32_t r = esp_random();
    int range = span * 2 + 1;
    return (int)(r % (uint32_t)range) - span;
}

float motion_jitter_envelope(float t)
{
    if (t <= 0.0f || t >= 1.0f) {
        return 0.0f;
    }
    /* A simple sine hump: zero at the endpoints, full in the middle. */
    float s = t < 0.5f ? (t * 2.0f) : ((1.0f - t) * 2.0f);
    return s;
}
