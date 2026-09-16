/* Composite USB HID device: keyboard, relative mouse, consumer control and an
 * absolute pointer, all on one interface distinguished by report ID.
 *
 * The absolute pointer is the reason macros can be told "go to pixel 1400,700":
 * a relative mouse has no idea where the cursor is, so dead reckoning to a
 * target is impossible once the host applies pointer acceleration. */
#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Report IDs, mirrored in the report descriptor. */
enum {
    DECK_RPT_KEYBOARD = 1,
    DECK_RPT_MOUSE    = 2,
    DECK_RPT_CONSUMER = 3,
    DECK_RPT_ABSPTR   = 4,
};

/* Mouse button bitmask, matching the protocol's wire encoding. */
enum {
    DECK_BTN_LEFT    = 1u << 0,
    DECK_BTN_RIGHT   = 1u << 1,
    DECK_BTN_MIDDLE  = 1u << 2,
    DECK_BTN_BACK    = 1u << 3,
    DECK_BTN_FORWARD = 1u << 4,
};

void usb_hid_init(void);

/* True once the host has enumerated and configured us. */
bool usb_hid_mounted(void);

/* Keyboard. `mods` is the HID modifier bitmask, `usage` a HID usage ID.
 * Up to six non-modifier keys may be held at once, as the boot layout allows. */
void usb_hid_key_down(uint8_t mods, uint8_t usage);
void usb_hid_key_up(uint8_t mods, uint8_t usage);
void usb_hid_keyboard_release_all(void);

/* Relative motion. Deltas are clamped to the int8 the boot mouse report uses;
 * callers that need more should emit several reports, which is what the motion
 * interpolator does anyway. */
void usb_hid_mouse_rel(int dx, int dy, int wheel, int hwheel);

/* Absolute pointer, in 0..DECK_ABS_MAX device units on both axes. */
void usb_hid_mouse_abs(uint16_t x, uint16_t y, int wheel);

/* Press or release mouse buttons. The new state is echoed on whichever
 * pointer report was last used, so a drag keeps working in both modes. */
void usb_hid_mouse_buttons(uint8_t mask, bool down);
uint8_t usb_hid_mouse_button_state(void);

/* Consumer control (media keys). Pass usage 0 to release. */
void usb_hid_consumer(uint16_t usage, bool down);

/* Panic release: every key, every mouse button, no media key. Called whenever a
 * macro is aborted so a half-finished drag cannot leave a button stuck down. */
void usb_hid_release_everything(void);

/* Drop the remembered absolute position, so the next move_abs lands directly
 * instead of gliding from an origin the host no longer agrees with. */
void usb_hid_forget_abs(void);

/* Last absolute position we reported, so a drag can start "from here". */
void usb_hid_last_abs(uint16_t *x, uint16_t *y);
bool usb_hid_have_abs(void);
