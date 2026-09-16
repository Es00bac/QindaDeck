# QindaDeck firmware

ESP-IDF 5.5 project for an ESP32-S3. Presents a composite USB HID device
(keyboard, relative mouse, consumer control, absolute pointer) over the chip's
native USB port, and takes its orders over Wi-Fi.

## Build and flash

The toolchain is not in Portage, so it lives in `$HOME` the way upstream
installs it — self-contained, with its own Python venv, touching nothing the
package manager owns:

```sh
git clone -b v5.5.5 --depth 1 --recursive https://github.com/espressif/esp-idf ~/esp/esp-idf
~/esp/esp-idf/install.sh esp32s3
```

Then, per shell:

```sh
. ~/esp/esp-idf/export.sh
idf.py build
idf.py -p /dev/ttyACM0 -b 921600 flash monitor
```

Flashing needs write access to the serial port. On Gentoo that means the
`dialout` group (`sudo usermod -aG dialout $USER`, then re-login). Until the
re-login takes effect, `sg dialout -c 'idf.py -p … flash'` works in the current
shell. `dev-embedded/esptool` provides a Portage-managed `esptool.py` for
lower-level work.

## Which USB port does what

The board has two USB connectors and they are not interchangeable:

| Port | Marking | Purpose |
| --- | --- | --- |
| UART bridge | often `UART` / `COM` | Flashing and the serial log. Enumerates as a CH343 (`1a86:55d3`) on `/dev/ttyACM0`. |
| Native USB | often `USB` / `OTG` | **The HID device.** This is the cable that must reach the computer you want to control. |

Powering the board from the UART port alone is fine for development, but the
deck does nothing to a host until the *native* port is cabled to it. `usb: false`
in `deckctl.py status` means exactly this.

## Layout

| File | Responsibility |
| --- | --- |
| `main.c` | Bring-up order, USB attach/detach watch |
| `usb_hid.c` | HID descriptors and report senders; four report IDs on one interface |
| `hid_keymap.c` | `"Ctrl+Shift+P"` → HID usages; ASCII → keystrokes; media key names |
| `macro.c` | Compiles macro JSON to a flat step array and interprets it on an 8 ms tick |
| `motion.c` | Easing curves and jitter shaping |
| `displays.c` | Display geometry; display-local pixels → absolute pointer units |
| `profiles.c` | Profile storage, validation, button dispatch |
| `defaults.c` | Built-in profiles, embedded from `profiles/*.json` |
| `net_link.c` | Wi-Fi, mDNS, the framed TCP server |
| `protocol.c` | JSON op dispatch and the binary input fast path |
| `provision.c` | HTTP setup page for first-run Wi-Fi credentials |

## Notes

- `CONFIG_FREERTOS_HZ=1000` is load-bearing. The macro engine beats every 8 ms;
  the default 100 Hz tick would quantise all motion to 10 ms steps.
- The HID interface uses report IDs, so it is not a boot-protocol keyboard. Some
  BIOS setup screens only speak boot protocol and may ignore it.
- PSRAM is left disabled. The board has 8 MB, but nothing here needs it and
  enabling it only adds octal/quad mode guesswork to the boot path.
