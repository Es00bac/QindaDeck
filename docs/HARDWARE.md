# Hardware

## The board

Developed and verified against an **ESP32-S3** (QFN56, revision v0.2) with 16 MB
of flash and 8 MB of embedded PSRAM, on a devkit carrying a CH343 USB-serial
bridge (`1a86:55d3`).

PSRAM is deliberately left disabled. Nothing here needs it, and enabling it only
adds octal-versus-quad mode guesswork to the boot path.

## Two USB ports, and they are not interchangeable

This is the single most common way to be confused by this project:

| Port | Usually marked | What it is for |
| --- | --- | --- |
| UART bridge | `UART`, `COM` | Flashing and the serial log. Appears as `/dev/ttyACM0`. |
| Native USB | `USB`, `OTG` | **The HID device.** The cable to the computer you want to control. |

Powering the board from the UART port alone is fine for development, but the deck
does nothing to any host until the *native* port is cabled to one. The app says
"USB not connected" and `deckctl.py status` reports `"usb": false` in exactly that
situation — the deck is alive and reachable, it simply has nobody to type at.

Both ports can be connected at once, and during development that is the useful
arrangement: flash and watch logs on one, drive the host on the other.

## Serial access on Gentoo

`/dev/ttyACM0` is `root:dialout` mode 0660, so flashing needs group membership:

```sh
sudo usermod -aG dialout "$USER"   # takes effect at next login
sg dialout -c 'idf.py -p /dev/ttyACM0 flash'   # works in the current shell
```

`/etc/udev/rules.d/99-qindadeck-esp32.rules` additionally tags the common bridge
chips and Espressif's native USB with `uaccess`, so whoever is logged in at the
seat can reach the board without being in `dialout` at all.

## Partition layout

16 MB flash, with the tail left unallocated so an OTA slot can be carved out later
without moving anything:

```
nvs        0x009000   24 KiB   Wi-Fi credentials, device name, paired devices
phy_init   0x00f000    4 KiB
factory    0x010000    4 MiB   app (about 1.4 MiB used with Bluetooth enabled)
storage    0x410000    2 MiB   SPIFFS: profile and remap JSON
```

## What the host sees

One USB HID interface carrying four report IDs:

| Report | Contents |
| --- | --- |
| 1 | Keyboard — modifier byte plus six keycodes |
| 2 | Relative mouse — buttons, dx, dy, wheel, pan |
| 3 | Consumer control — one 16-bit media usage |
| 4 | Absolute pointer — buttons, 16-bit X and Y over 0..32767, wheel |

The interface requests a 1 ms polling interval, so the macro engine's 8 ms beat is
never limited by the endpoint.

Because the descriptor uses report IDs it is not a boot-protocol keyboard. Most
firmware setup screens handle it, but some only speak boot protocol and will
ignore it.

### Why an absolute pointer

A relative mouse cannot be told to go to a particular pixel: the device has no
idea where the cursor currently is, and the host's pointer acceleration makes dead
reckoning useless. An absolute pointer names the destination outright, so a macro
lands on the pixel it was recorded on.

Report 4 is declared under Generic Desktop → Mouse → Pointer with absolute 16-bit
axes, which is the same shape KVM-over-IP dongles use — hosts accept it without
deciding the device is a graphics tablet.

One consequence worth knowing: the device only knows the position it last
*reported*. After a reboot, the first `move_abs` lands directly on its target
instead of gliding, because there is no origin to glide from. Every later move
interpolates. Moving the physical mouse in between also desynchronises the
remembered position from the real cursor, and the next absolute move will jump
from the remembered point — unavoidable when two pointers share one host.
