/* The built-in profile set.
 *
 * Each profile is a real .json file under profiles/, embedded at build time
 * rather than pasted into a C string literal: the files stay lintable, diffable
 * and shareable with the Android app's asset copies. */
#include <stdlib.h>

#define EMBED(name)                                       \
    extern const char name##_start[] asm("_binary_" #name "_json_start"); \
    extern const char name##_end[] asm("_binary_" #name "_json_end")

EMBED(numpad);
EMBED(fkeys);
EMBED(media);
EMBED(photoshop);
EMBED(sloom);
EMBED(qindaqt);
EMBED(mouse);

/* EMBED_TXTFILES null-terminates each blob, so the start pointer is directly
 * usable as a C string. */
const char *const deck_default_profiles[] = {
    numpad_start,
    fkeys_start,
    media_start,
    photoshop_start,
    sloom_start,
    qindaqt_start,
    mouse_start,
};

const int deck_default_profile_count =
    (int)(sizeof(deck_default_profiles) / sizeof(deck_default_profiles[0]));
