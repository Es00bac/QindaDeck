# Macro step reference

A macro is an ordered list of steps plus an optional `loop` count. Steps run on a
dedicated task at a fixed **125 Hz tick** (8 ms), which is also the USB report
rate. Any step may carry `after` (ms to idle once the step completes).

```json
{ "name": "Export PNG", "loop": 1, "steps": [ … ] }
```

## Keyboard

| step | fields | effect |
| --- | --- | --- |
| `tap` | `keys` | press and release a chord: `"Ctrl+Shift+S"`, `"F13"`, `"Numpad7"` |
| `down` / `up` | `keys` | half of a chord, for holds spanning other steps |
| `text` | `s` | type a string; US layout, handles shifted punctuation |
| `consumer` | `media` | media key, e.g. `play_pause`, `vol_up`, `next` |

Key names are case-insensitive. Modifiers: `Ctrl`, `Shift`, `Alt`, `Meta`
(`Super`/`Win`/`Cmd` are accepted aliases); right-hand variants are `RCtrl`,
`RShift`, `RAlt`, `RMeta`. Everything else resolves through the table in
`firmware/main/hid_keymap.c`, which covers A–Z, 0–9, F1–F24, the numpad,
navigation, and punctuation.

## Mouse

| step | fields | effect |
| --- | --- | --- |
| `move_abs` | `x`, `y`, `display?`, `dur`, `ease?`, `jitter?` | travel to a pixel |
| `move_rel` | `dx`, `dy`, `dur`, `ease?`, `jitter?` | travel by a delta |
| `mdown` / `mup` | `btn` | hold or release a mouse button |
| `click` | `btn`, `count?`, `gap?` | click, optionally multi-click |
| `drag` | `from?`, `to`, `btn`, `dur`, `ease?`, `display?` | press, travel, release |
| `scroll` | `amount`, `dur?`, `hamount?` | wheel, spread over `dur` |

`btn` is `left`, `right`, `middle`, `back`, or `forward`.

`dur` is milliseconds of travel. It is the whole point: a `move_abs` with
`dur: 600` emits ~75 interpolated reports over 600 ms, so the host sees a cursor
gliding across the screen. `dur: 0` is the teleport escape hatch.

`ease` shapes that travel — `linear`, `in`, `out`, `in_out` (default `in_out`).
`jitter` adds up to N pixels of per-tick wobble, which makes drags look
hand-made rather than robotic.

`drag` with no `from` starts wherever the pointer already is, so you can compose
`move_abs` → `drag`.

## Control flow

| step | fields | effect |
| --- | --- | --- |
| `delay` | `ms` | idle |
| `repeat` | `count`, `steps` | run a nested block N times |

Nesting is capped at 4 levels and total expanded steps at 2048, so a malformed
profile cannot wedge the device. A running macro is pre-emptable: `stop_macro`,
or any new `press`, aborts it and releases every held key and button first, so a
half-finished drag never leaves a button stuck down.

## Worked example

Select a region in an image editor, feather it, and save:

```json
{
  "name": "Feather + save",
  "steps": [
    { "t": "tap",      "keys": "M", "after": 80 },
    { "t": "move_abs", "x": 420, "y": 300, "display": "main", "dur": 300 },
    { "t": "drag",     "to": { "x": 980, "y": 720 }, "btn": "left",
                       "dur": 700, "ease": "in_out", "jitter": 1 },
    { "t": "tap",      "keys": "Shift+F6", "after": 200 },
    { "t": "text",     "s": "2" },
    { "t": "tap",      "keys": "Return", "after": 250 },
    { "t": "tap",      "keys": "Ctrl+S" }
  ]
}
```
