# QindaDeck wire protocol (v1)

Transport is a single TCP stream, device listening on **port 8437**. The phone is
the client. One connection at a time is served; a second connection displaces the
first (so a reconnect after a phone sleep always wins).

## Discovery

The device advertises over mDNS:

```
hostname:  qindadeck.local
service:   _qindadeck._tcp  port 8437
TXT:       ver=1  id=<last 3 MAC bytes, hex>  name=<user label>
```

If no Wi-Fi credentials are stored, the device instead raises a SoftAP named
`QindaDeck-<id>` (WPA2, passphrase `qindadeck`) and serves a provisioning page at
`http://192.168.4.1/`. `POST /api/wifi` with `{"ssid":"…","pass":"…"}` stores the
credentials and reboots into station mode.

## Framing

Every frame on the stream:

```
 0        4        5
 +--------+--------+--------------------------------+
 | len:u32| type:u8|  payload (len-1 bytes)         |
 +--------+--------+--------------------------------+
   big-endian        len counts the type byte
```

`len` is capped at 64 KiB. Oversized frames drop the connection.

| type | meaning |
| --- | --- |
| `0x01` | JSON control message, UTF-8 |
| `0x02` | binary input event (low latency path) |
| `0x03` | keepalive (empty payload) |

## Control messages — type 0x01

Requests carry a client-chosen `id`; the reply echoes it. `id` 0 is reserved for
unsolicited device events.

```json
→ {"id":7,"op":"hello"}
← {"id":7,"ok":true,"fw":"1.0.0","chip":"esp32s3","mac":"7c:df:a1:e3:10:44",
   "usb":true,"sd":true,"sd_mb":29815,"heap":184320,"active":"qindaqt","page":0,
   "profiles":6}
```

| op | payload | reply |
| --- | --- | --- |
| `hello` | — | device identity + state; `sd` reports a card live as the USB LUN, `sd_mb` its capacity, `sd_owner` who holds it |
| `get_status` | — | `heap`, `uptime`, `usb`, `running` (macro name or null) |
| `list_profiles` | — | `profiles`: `[{slug,name,icon,accent,pages}]` |
| `get_profile` | `slug` | `profile`: full object |
| `put_profile` | `profile` | `ok` — upsert, persisted to flash |
| `del_profile` | `slug` | `ok` |
| `set_active` | `slug`, `page` | `ok` |
| `press` | `slug?`, `page?`, `index`, `state` | fires a button; `state` is `down`/`up`/`tap` |
| `run_macro` | `macro` | runs an ad-hoc macro — the editor's Test button |
| `stop_macro` | — | aborts, then releases every key and mouse button |
| `get_displays` | — | `displays` config object |
| `put_displays` | `displays` | `ok` — persisted |
| `set_name` | `name` | `ok` — also updates the mDNS TXT record |
| `wifi_scan` | — | `nets`: `[{ssid,rssi,auth}]` |
| `wifi_set` | `ssid`, `pass` | `ok`, then reboots |
| `wifi_forget` | — | `ok`, reboots into SoftAP |
| `reboot` | — | `ok`, then reboots |
| `factory_reset` | — | rewrites the built-in profiles |
| `sd_status` | — | `present`, `capacity_mb`, `owner` (`usb`/`app`/`none`) |
| `sd_take` | — | takes the card for firmware file I/O; the USB host sees its drive eject. `filesystem` says whether a readable FAT/exFAT mounted |
| `sd_release` | — | hands the card back to the USB host. Also happens automatically after one idle minute |
| `sd_list` | `path` | `entries`: `[{name,dir,size,mtime}]`, capped at 256 with `truncated` |
| `sd_read` | `path`, `offset?`, `max?` | `size`, `offset`, base64 `data` (≤ 24 KiB), `eof` |
| `sd_write` | `path`, `offset`, base64 `data` (≤ 24 KiB) | `written`. Offset 0 creates/truncates; otherwise resumes in place |
| `sd_delete` | `path` | `ok` — files and empty directories |
| `sd_mkdir` | `path` | `ok` |
| `bt_prov` | `seconds?` (default 120, 0 = until reboot) | opens the BLE provisioning window; the deck advertises a small GATT service the app writes Wi-Fi credentials to. Also open by itself while no credentials are stored |
| `bt_scan` | `seconds?` | `devices`: `[{addr,name,rssi,usage,what,addr_type,appearance}]` |
| `bt_pair` | `addr`, `addr_type?`, `name?` | starts connecting; outcome arrives as a `bt` event |
| `bt_forget` | `addr` | drops the bond and stops reconnecting |
| `bt_list` | — | `devices`: remembered devices with `connected` |
| `list_remaps` | — | `remaps`: `[{addr,name,bindings,passthrough}]` |
| `get_remap` | `addr` | `remap`, or null when the device has no bindings yet |
| `put_remap` | `remap` | validates and stores |
| `del_remap` | `addr` | removes the bindings |
| `learn` | `on`, `mute?` | streams `src_input` events; `mute` (default true) suppresses bindings while capturing |

Unsolicited events use `id` 0 and an `ev` key:

```json
{"id":0,"ev":"usb","mounted":true}
{"id":0,"ev":"macro","state":"start","name":"Export PNG"}
{"id":0,"ev":"macro","state":"done","name":"Export PNG"}
{"id":0,"ev":"macro","state":"abort","name":"Export PNG","reason":"stopped"}
{"id":0,"ev":"bt","state":"connected","addr":"e4:17:d8:2a:19:7c","name":"Stratus+"}
{"id":0,"ev":"bt","state":"disconnected","addr":"e4:17:d8:2a:19:7c"}
{"id":0,"ev":"bt","state":"battery","addr":"e4:17:d8:2a:19:7c","level":82}
{"id":0,"ev":"bt","state":"failed","addr":"","reason":"could not connect"}
```

A client is also sent an unsolicited `hello` the moment it connects, so the app
does not have to ask for state it is about to be given.

## SD card file access — the ownership contract

The card can serve exactly one master at a time: the USB host as a block
device, or the firmware as a FAT filesystem. Concurrent access from both is
how cards get corrupted, so file operations follow a strict protocol:

1. `sd_take` — the host's drive ejects; `/sdcard` mounts on the firmware.
2. `sd_list` / `sd_read` / `sd_write` / `sd_delete` / `sd_mkdir` — the work.
   Paths must be absolute and contain no `..`. Binary payloads travel as
   base64 in chunks of at most 24 KiB.
3. `sd_release` — the host gets its drive back.

One idle minute without an sd op releases automatically, so a crashed client
cannot strand the host's drive. Every file op also resets that clock.

## BLE provisioning — `bt_prov`

While the window is open, the deck advertises connectable BLE (same
`QindaDeck <id>` name) carrying service `51DA1000-0DEC-4000-A000-0000000DECA0`:

| characteristic | access | purpose |
| --- | --- | --- |
| `51DA1001-…` | write | SSID, ≤ 32 bytes UTF-8 |
| `51DA1002-…` | write | password, ≤ 64 bytes UTF-8 |
| `51DA1003-…` | write `0x01` | apply: store both and reboot onto the network |
| `51DA1004-…` | read, notify | state: 0 idle, 1 ssid staged, 2 ready, 3 applying |

The link is meant to be thrown away: the phone disconnects, the deck reboots,
and the radio returns to being a BLE HID host for paired controllers.

## Learn mode — `src_input`

While `learn` is on, every input report from a paired device is summarised to the
client. This is how the app offers a binding predicate without anyone having to
know their controller's report layout:

```json
{"id":0,"ev":"src_input","addr":"e4:17:d8:2a:19:7c","report":1,"len":9,"usage":8,
 "bits":[{"byte":4,"bit":0,"down":true}],
 "bytes":[{"byte":4,"val":1,"prev":0,"analog":false},
          {"byte":2,"val":214,"prev":128,"analog":true}],
 "keys":[],
 "raw":"01008ed6010000000"}
```

`bits` lists every bit that changed and whether it went high; `bytes` lists every
byte that changed, flagged `analog` when it moved by more than 8, which separates
a stick from a button without guessing. `keys` carries the currently-held keyboard
usages when the device is a keyboard, with modifiers reported as their canonical
usages `0xE0`..`0xE7`. `raw` is the whole report, for the awkward device that
fits none of the above.

## Binary input events — type 0x02

For live control the JSON path is skipped. First payload byte is the kind:

| kind | payload | note |
| --- | --- | --- |
| `0x10` MOUSE_REL | `i16 dx, i16 dy, i8 wheel, i8 hwheel, u8 buttons` | trackpad |
| `0x11` MOUSE_ABS | `u16 x, u16 y, i8 wheel, u8 buttons` | x/y normalized 0..32767 |
| `0x12` KEY | `u8 down, u8 mods, u8 usage` | raw HID usage |
| `0x13` CONSUMER | `u16 usage, u8 down` | media keys |

All multi-byte fields are big-endian. `buttons` is a bitmask: bit0 left,
bit1 right, bit2 middle, bit3 back, bit4 forward.

These are fire-and-forget; the device never acknowledges them. Dropping one is
preferable to queueing stale motion, so the device coalesces relative deltas
that arrive faster than the 125 Hz report tick.

## Coordinate mapping

Macros store coordinates in **display-local pixels** plus a `display` id, which
survives a monitor swap. The device resolves them against the stored display
config:

```
virtual_x = display.x + local_x
report_x  = virtual_x * 32767 / virtual_width
```

`virtual_width`/`virtual_height` are the bounding box of all configured
displays. See `docs/MACROS.md` for the step reference.
