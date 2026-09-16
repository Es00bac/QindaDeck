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

## Pairing a controller to the deck

The deck is also a BLE HID *host*: a gamepad or keyboard bonds to the ESP32
itself, and its buttons are remapped to anything a deck key can do, macros
included. Pairing is driven from the app, but the radio is the deck's — so the app
needs no Bluetooth permission and a paired controller keeps working with the phone
switched off.

The S3 has **no Bluetooth Classic hardware**, so this is BLE only. See
`docs/BLUETOOTH.md` for what that rules in and out, and for how bindings are
captured by simply pressing the button you want to remap.

## Getting started

```sh
# firmware
. ~/esp/esp-idf/export.sh
cd firmware && idf.py build && idf.py -p /dev/ttyACM0 flash monitor

# app
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# poke the device without the app
tools/deckctl.py status
tools/deckctl.py drag 300 300 1200 800 --dur 900
```

A deck with no Wi-Fi saved raises its own access point (`QindaDeck-XXXXXX`,
passphrase `qindadeck`) and serves a setup page at `http://192.168.4.1/`.

## Status

Verified on the board: seven profiles load, mDNS discovery resolves, macro step
timing lands within a few ms of its declared duration, and the app connects,
renders the deck, fires keys, edits macros and drives a BLE scan end to end.

Not yet exercised against real hardware: the USB HID output itself (needs the
native USB port cabled to a host) and a live controller pairing (needs one in
pairing mode).

Known gaps: profile-level saved macros can be run from the app but not edited
there, and there is no OTA firmware update — the partition table leaves room for
one.

See `docs/HARDWARE.md` for the wiring and which USB port does what,
`docs/PROTOCOL.md` for the wire format, `docs/MACROS.md` for the step reference,
and `docs/BLUETOOTH.md` for pairing. Build instructions for each half live in
`firmware/README.md` and `android/README.md`.
