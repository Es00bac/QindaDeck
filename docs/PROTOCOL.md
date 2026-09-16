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
   "usb":true,"heap":184320,"active":"numpad","page":0,"profiles":6}
```

| op | payload | reply |
| --- | --- | --- |
| `hello` | — | device identity + state |
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
| `factory_reset` | — | wipes NVS + profile storage |

Unsolicited events use `id` 0 and an `ev` key:

```json
{"id":0,"ev":"usb","mounted":true}
{"id":0,"ev":"macro","state":"start","name":"Export PNG"}
{"id":0,"ev":"macro","state":"done","name":"Export PNG"}
{"id":0,"ev":"macro","state":"abort","name":"Export PNG","reason":"stopped"}
```

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
