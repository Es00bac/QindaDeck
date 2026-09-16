/* Display geometry, and the pixel-to-absolute-pointer mapping built on it.
 *
 * Macros store coordinates as display-local pixels plus a display id. That
 * survives a monitor swap, a resolution change, or plugging the deck into a
 * different machine: re-describe the displays and every existing macro still
 * lands where it was recorded. */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "cJSON.h"

#define DECK_MAX_DISPLAYS 8

typedef struct {
    char id[24];
    char name[40];
    int w, h;
    int x, y;        /* top-left within the virtual desktop */
    float scale;
    bool primary;
} deck_display_t;

void displays_init(void);

/* Replace the whole configuration from a parsed `displays` object and persist
 * it. Returns false (and keeps the old config) if the object is malformed. */
bool displays_set_json(const cJSON *obj, char *err, size_t errlen);

/* Serialise the current configuration. Caller owns the returned cJSON. */
cJSON *displays_get_json(void);

/* Convert display-local pixels to the 0..32767 absolute pointer range.
 * `id` may be NULL or unknown, in which case the active (or primary) display is
 * used, so a macro authored on a single-monitor setup keeps working. */
void displays_to_abs(const char *id, int px, int py, uint16_t *ax, uint16_t *ay);

/* Inverse, for reporting the current pointer position back to the editor. */
void displays_from_abs(uint16_t ax, uint16_t ay, const char **id, int *px, int *py);

/* Bounding box across every configured display. */
void displays_virtual_size(int *w, int *h);
