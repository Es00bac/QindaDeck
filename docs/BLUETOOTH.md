# Pairing a controller to the deck

The deck can be a Bluetooth HID **host** as well as a USB HID device: a gamepad or
keyboard bonds to the ESP32 itself, and its buttons are remapped to anything a
deck key can do. Pairing is driven from the Android app, but the radio link is the
deck's — which is why the app requests no Bluetooth permission, and why a paired
controller keeps working with the phone switched off and out of the room.

## What can pair, and what cannot

The ESP32-S3's radio is **Bluetooth Low Energy only**. The chip has no Bluetooth
Classic hardware, so this is a hardware limit rather than a firmware gap:

| Works | Does not work |
| --- | --- |
| SteelSeries Stratus+ (Bluetooth mode) | DualShock 4, DualSense |
| 8BitDo pads in BLE / Switch modes | Xbox controllers older than the BLE models |
| Most Bluetooth keyboards and mice | Anything Classic-only, including most BT audio |

If a device does not appear in a scan, the usual cause is not range: controllers
advertise for only a minute or two after you hold their pairing combination, and
they stop advertising the moment they reconnect to something they already know.
Turn Bluetooth off on whatever they were last paired with.

## Binding without knowing the report layout

A gamepad's HID report layout is whatever its vendor chose. Rather than parse an
arbitrary report descriptor well enough to name "the A button", bindings are
written as predicates over the raw report:

| Predicate | Fires when |
| --- | --- |
| `{"kind":"btn","byte":4,"bit":0}` | that bit goes high |
| `{"kind":"axis","byte":2,"dir":"lo","thr":64}` | that byte crosses the threshold |
| `{"kind":"key","usage":4}` | that keyboard usage is held |

You never write those by hand. The app turns on learn mode, the deck streams a
diff of each incoming report, and the editor offers the predicate that just
changed — so you bind a button by pressing it. A byte that jumped by more than 8
is offered as a stick or trigger, a single changed bit as a button, and you pick
whichever you actually pressed.

Bindings get the real press and release, so a `hold` action holds for exactly as
long as the button is down, and a macro bound to a trigger runs once per pull
rather than repeating.

## Keyboards: passthrough

A remap document can set `passthrough`, which forwards every key **nothing has
claimed** straight to the USB side. That makes a BLE keyboard work normally except
for the keys you rebound — remap Caps Lock to a macro and keep typing with the
rest. Unclaimed keys are tracked as a set, so releasing one never releases the
others.

## Coexistence, and the one cost

Wi-Fi and BLE share one radio and one antenna path. With the coexistence arbiter
enabled, `WIFI_PS_NONE` starves BLE badly enough that the **Wi-Fi association
itself** fails — repeated disconnects with reason 2 — whether or not anything is
paired; the controller merely running is enough. So Wi-Fi power save is fixed at
modem sleep and is not tunable.

It costs little in practice, because an active control connection keeps the radio
awake: a button press measured ~18 ms round trip from the phone to the device's
reply, with Bluetooth enabled.

## Limits

Four devices can be remembered at once, and four remap documents held in RAM.
Remembered devices are re-opened on a slow loop, so switching a controller on
reconnects it without the phone being involved. A disconnect releases anything the
device was holding, so a remapped key cannot stay down after a controller wanders
out of range.
