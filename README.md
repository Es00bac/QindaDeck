# QindaDeck

An ESP32-S3 turned into a USB keyboard, mouse and media controller, driven over
Wi-Fi from an Android companion app. Configurable macro pads, several built-in
profile pages, and mouse macros that *travel* — interpolated motion over time,
with press-drag-release, not cursor teleports.

The board presents itself to the host PC as an ordinary composite USB HID
device, so it needs no driver and works at the firmware level: BIOS, login
screens, and any OS see a plain keyboard and mouse.

```
  ┌───────────┐   Wi-Fi / TCP 8437    ┌──────────────┐   USB HID    ┌──────┐
  │  Android  │ ────────────────────► │  ESP32-S3    │ ───────────► │  PC  │
  │  app      │ ◄──────────────────── │  (USB OTG)   │              │      │
  └───────────┘   events / status     └──────────────┘              └──────┘
```

## Layout

| Path | What |
| --- | --- |
| `firmware/` | ESP-IDF 5.5 project. TinyUSB composite HID, macro engine, Wi-Fi link. |
| `android/` | Kotlin + Jetpack Compose app. Deck UI, macro editor, display mapping. |
| `protocol/` | `qindadeck.schema.json` — the profile/macro schema both sides honour. |
| `docs/` | Protocol wire format, hardware notes, macro reference. |
| `tools/` | Host-side helpers (`deckctl.py`) for poking the device without the app. |

## Why absolute pointer coordinates

A relative mouse cannot be told to "go to (1400, 700)" — the device has no idea
where the cursor currently is, and pointer acceleration makes dead reckoning
useless. So the HID descriptor exposes **both**:

- a **relative** mouse (report 2) for live trackpad control, and
- an **absolute** pointer (report 4, 0..32767 on each axis) for macros.

Macros therefore land on the exact pixel you recorded, and the Android app
converts your monitor's pixels into that normalized space using the display
geometry you configure. Change monitors, re-map the display, and the same macro
still hits the right spot.

## Status

See `docs/HARDWARE.md` for the wiring and which USB port does what, and
`docs/PROTOCOL.md` for the wire format. Build instructions for each half live in
`firmware/README.md` and `android/README.md`.
