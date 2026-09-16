# QindaDeck

An ESP32-S3 turned into a USB keyboard, mouse, media controller **and SD card
reader**, driven over Wi-Fi from an Android companion app. Configurable macro
pads, several built-in profile pages, voice typing from the phone's mic, and
mouse macros that *travel* — interpolated motion over time, with
press-drag-release, not cursor teleports.

The board presents itself to the host PC as an ordinary composite USB device —
HID plus mass storage — so it needs no driver and works at the firmware level:
BIOS, login screens, and any OS see a plain keyboard, mouse, and card reader.
A micro SD card in the slot shows up as a USB drive, which means the deck can
carry a live Linux image and boot the very machine it is typing on.

```
  ┌───────────┐   Wi-Fi / TCP 8437    ┌──────────────┐   USB HID    ┌──────┐
  │  Android  │ ────────────────────► │  ESP32-S3    │ ───────────► │  PC  │
  │  app      │ ◄──────────────────── │  (USB OTG)   │   USB MSC    │      │
  └───────────┘   events / status     └──────────────┘ ───────────► └──────┘
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

## Voice typing

The app's Voice tab turns the phone into a wireless keyboard you talk at.
Speech is recognized on the phone; each finished phrase is typed on the host
through the deck as ordinary keystrokes, so it lands wherever the cursor is —
editor, terminal, chat, login box. An "Enter after phrase" toggle makes it a
walkie-talkie for shells and chats. Only text leaves the phone, never audio.

## Booting from the deck's SD card

With a card in the slot, the deck enumerates as a USB drive *next to* the
keyboard and mouse. Write a live image to the card (from any computer, via the
deck itself or a card reader), reboot the host, pick the drive in the boot
menu — the deck's keyboard works in that same menu. Two honest caveats: the
link is full-speed USB, so an image loads in minutes rather than seconds; and
the keyboard speaks report-ID HID, which some BIOS setup screens that insist
on boot protocol will ignore (GRUB and Linux handle it fine). See
`docs/HARDWARE.md` for the slot wiring — it is board-specific and must match
`deck_config.h` before the card will probe.

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

Verified on the board: six profiles load, mDNS discovery resolves, macro step
timing lands within a few ms of its declared duration, and the app connects,
renders the deck, fires keys, edits macros, types spoken phrases and drives a
BLE scan end to end. The composite HID+MSC descriptor enumerates and the drive
appears as an empty reader when the slot pins are unwired or no card is in.

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
