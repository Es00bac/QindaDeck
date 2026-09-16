/* Easing and interpolation for pointer travel.
 *
 * Kept separate from the macro engine so the curves can be reasoned about (and
 * unit-tested on a host) without dragging in FreeRTOS or USB. */
#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    DECK_EASE_LINEAR = 0,
    DECK_EASE_IN,
    DECK_EASE_OUT,
    DECK_EASE_IN_OUT,
} deck_ease_t;

deck_ease_t motion_ease_from_name(const char *name);

/* Map linear progress 0..1 through the curve. Input is clamped. */
float motion_ease(deck_ease_t e, float t);

/* Deterministic per-device jitter source. A real hand never draws a straight
 * line, and a perfectly straight 600 ms drag is one of the tells that a macro
 * is driving the pointer. `span` is the peak deviation in pixels. */
int motion_jitter(int span);

/* Jitter is tapered to zero at both ends of a move so the start and end points
 * stay exact -- the whole promise of absolute positioning. */
float motion_jitter_envelope(float t);
