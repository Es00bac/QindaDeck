package org.qinda.deck.net

/**
 * Wire constants, mirrored from firmware/main/net_link.h and docs/PROTOCOL.md.
 *
 * Frames are `[u32 length][u8 type][payload]`, big-endian, where length counts
 * the type byte. Control traffic is JSON; live input takes a compact binary path
 * so a dragging finger does not pay for JSON encoding on every sample.
 */
object Frame {
    const val JSON = 0x01
    const val INPUT = 0x02
    const val PING = 0x03
    const val MAX_LEN = 64 * 1024
}

/** Binary input event kinds. */
object InputKind {
    const val MOUSE_REL = 0x10
    const val MOUSE_ABS = 0x11
    const val KEY = 0x12
    const val CONSUMER = 0x13
}

/** Mouse button bitmask, matching DECK_BTN_* on the device. */
object MouseButton {
    const val LEFT = 1
    const val RIGHT = 2
    const val MIDDLE = 4
    const val BACK = 8
    const val FORWARD = 16
}

object Net {
    const val PORT = 8437
    const val SERVICE_TYPE = "_qindadeck._tcp"
    /** Absolute pointer range on both axes; see DECK_ABS_MAX. */
    const val ABS_MAX = 32767
}

/** Keyboard modifier bits for [InputKind.KEY], matching the HID report. */
object KeyMod {
    const val LCTRL = 1
    const val LSHIFT = 2
    const val LALT = 4
    const val LMETA = 8
    const val RCTRL = 16
    const val RSHIFT = 32
    const val RALT = 64
    const val RMETA = 128
}

/**
 * HID keyboard usages for the keys voice typing needs as edges rather than
 * text — Enter, Backspace and friends have no printable ASCII form.
 */
object HidUsage {
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val DELETE = 0x4C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
}
