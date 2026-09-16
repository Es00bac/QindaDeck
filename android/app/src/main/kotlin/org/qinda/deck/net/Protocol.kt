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
