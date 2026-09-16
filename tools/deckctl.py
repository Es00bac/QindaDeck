#!/usr/bin/env python3
"""deckctl — talk to a QindaDeck over the wire, without the phone.

Useful for bringing a board up, checking a macro before committing it to a
profile, and as a worked reference for the protocol in docs/PROTOCOL.md.

    deckctl.py status
    deckctl.py profiles
    deckctl.py get numpad
    deckctl.py press 4 --profile numpad
    deckctl.py macro '{"steps":[{"t":"move_abs","x":900,"y":500,"dur":600}]}'
    deckctl.py macro-file my_macro.json
    deckctl.py displays
    deckctl.py put-displays layout.json
    deckctl.py move 400 300 --dur 800        # absolute travel, interpolated
    deckctl.py nudge 120 0                   # relative motion
    deckctl.py type "hello world"
    deckctl.py key Ctrl+Shift+P
    deckctl.py watch                         # stream unsolicited events
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import time

DEFAULT_PORT = 8437
FRAME_JSON = 0x01
FRAME_INPUT = 0x02
FRAME_PING = 0x03

IN_MOUSE_REL = 0x10
IN_MOUSE_ABS = 0x11
IN_KEY = 0x12
IN_CONSUMER = 0x13

BUTTONS = {"left": 1, "right": 2, "middle": 4, "back": 8, "forward": 16}


def discover(timeout: float = 4.0) -> str | None:
    """Find a deck via mDNS. Falls back to None so the caller can ask for --host."""
    try:
        from zeroconf import ServiceBrowser, Zeroconf
    except ImportError:
        # zeroconf is optional; avahi-browse covers the common Linux case.
        import shutil
        import subprocess

        if not shutil.which("avahi-browse"):
            return None
        try:
            out = subprocess.run(
                ["avahi-browse", "-rpt", "_qindadeck._tcp"],
                capture_output=True, text=True, timeout=timeout,
            ).stdout
        except subprocess.SubprocessError:
            return None
        for line in out.splitlines():
            if line.startswith("="):
                parts = line.split(";")
                if len(parts) > 8 and parts[2] == "IPv4":
                    return parts[7]
        return None

    found: list[str] = []

    class Listener:
        def add_service(self, zc, type_, name):
            info = zc.get_service_info(type_, name, timeout=int(timeout * 1000))
            if info and info.addresses:
                found.append(socket.inet_ntoa(info.addresses[0]))

        def update_service(self, *a):
            pass

        def remove_service(self, *a):
            pass

    zc = Zeroconf()
    ServiceBrowser(zc, "_qindadeck._tcp.local.", Listener())
    deadline = time.time() + timeout
    while time.time() < deadline and not found:
        time.sleep(0.1)
    zc.close()
    return found[0] if found else None


class Deck:
    def __init__(self, host: str, port: int = DEFAULT_PORT, timeout: float = 6.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.buf = b""
        self.next_id = 1

    def close(self) -> None:
        self.sock.close()

    # --- framing ---------------------------------------------------------
    def _send(self, ftype: int, payload: bytes) -> None:
        self.sock.sendall(struct.pack(">IB", len(payload) + 1, ftype) + payload)

    def _read_frame(self) -> tuple[int, bytes]:
        while len(self.buf) < 5:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("deck closed the connection")
            self.buf += chunk
        length, ftype = struct.unpack(">IB", self.buf[:5])
        need = 5 + length - 1
        while len(self.buf) < need:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("deck closed mid-frame")
            self.buf += chunk
        payload = self.buf[5:need]
        self.buf = self.buf[need:]
        return ftype, payload

    def recv_json(self) -> dict:
        while True:
            ftype, payload = self._read_frame()
            if ftype == FRAME_JSON:
                return json.loads(payload.decode())

    def call(self, op: str, **kw) -> dict:
        """Send a request and return its reply, skipping unsolicited events."""
        rid = self.next_id
        self.next_id += 1
        self._send(FRAME_JSON, json.dumps({"id": rid, "op": op, **kw}).encode())
        while True:
            msg = self.recv_json()
            if msg.get("id") == rid:
                return msg
            # id 0 is a device event; print it so nothing is silently lost.
            print(f"  · event: {json.dumps(msg)}", file=sys.stderr)

    # --- binary input ----------------------------------------------------
    def mouse_rel(self, dx: int, dy: int, wheel: int = 0, hwheel: int = 0, buttons: int = 0):
        self._send(FRAME_INPUT, struct.pack(">BhhbbB", IN_MOUSE_REL, dx, dy,
                                            wheel, hwheel, buttons))

    def mouse_abs(self, x: int, y: int, wheel: int = 0, buttons: int = 0):
        self._send(FRAME_INPUT, struct.pack(">BHHbB", IN_MOUSE_ABS, x, y, wheel, buttons))

    def key(self, down: bool, mods: int, usage: int):
        self._send(FRAME_INPUT, struct.pack(">BBBB", IN_KEY, 1 if down else 0, mods, usage))

    def ping(self):
        self._send(FRAME_PING, b"")


def ok(reply: dict) -> dict:
    if not reply.get("ok"):
        print(f"error: {reply.get('err', 'request failed')}", file=sys.stderr)
        sys.exit(1)
    return reply


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", help="deck address; discovered over mDNS when omitted")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("status", help="device identity and state")
    sub.add_parser("profiles", help="list stored profiles")
    sub.add_parser("displays", help="show the display layout")
    sub.add_parser("stop", help="abort the running macro and release everything")
    sub.add_parser("reboot")
    sub.add_parser("factory-reset", help="restore the built-in profiles")
    sub.add_parser("scan", help="list visible Wi-Fi networks")
    sub.add_parser("watch", help="print device events until interrupted")

    p = sub.add_parser("get", help="dump one profile as JSON"); p.add_argument("slug")
    p = sub.add_parser("put", help="upload a profile JSON file"); p.add_argument("path")
    p = sub.add_parser("delete"); p.add_argument("slug")
    p = sub.add_parser("put-displays", help="upload a display layout JSON file")
    p.add_argument("path")

    p = sub.add_parser("activate"); p.add_argument("slug")
    p.add_argument("--page", type=int, default=0)

    p = sub.add_parser("press", help="fire a button by grid index")
    p.add_argument("index", type=int)
    p.add_argument("--profile"); p.add_argument("--page", type=int)
    p.add_argument("--state", default="tap", choices=["down", "up", "tap"])

    p = sub.add_parser("macro", help="run an ad-hoc macro given as JSON")
    p.add_argument("json")
    p = sub.add_parser("macro-file", help="run an ad-hoc macro from a file")
    p.add_argument("path")

    p = sub.add_parser("move", help="travel to a pixel on a display")
    p.add_argument("x", type=int); p.add_argument("y", type=int)
    p.add_argument("--dur", type=int, default=600)
    p.add_argument("--ease", default="in_out", choices=["linear", "in", "out", "in_out"])
    p.add_argument("--jitter", type=int, default=0)
    p.add_argument("--display")

    p = sub.add_parser("nudge", help="relative travel")
    p.add_argument("dx", type=int); p.add_argument("dy", type=int)
    p.add_argument("--dur", type=int, default=300)

    p = sub.add_parser("drag", help="press, travel, release")
    p.add_argument("x1", type=int); p.add_argument("y1", type=int)
    p.add_argument("x2", type=int); p.add_argument("y2", type=int)
    p.add_argument("--dur", type=int, default=800)
    p.add_argument("--btn", default="left", choices=list(BUTTONS))

    p = sub.add_parser("type", help="type a string"); p.add_argument("text")
    sub.add_parser("sd-status", help="SD card presence, capacity and owner")
    sub.add_parser("sd-take", help="take the card from the USB host for file I/O")
    sub.add_parser("sd-release", help="give the card back to the USB host")
    p = sub.add_parser("sd-ls", help="list a directory"); p.add_argument("path", nargs="?", default="/")
    p = sub.add_parser("sd-get", help="download a file"); p.add_argument("remote"); p.add_argument("local")
    p = sub.add_parser("sd-put", help="upload a file"); p.add_argument("local"); p.add_argument("remote", nargs="?")
    p = sub.add_parser("sd-rm", help="delete a file"); p.add_argument("remote")
    p = sub.add_parser("key", help="tap a chord"); p.add_argument("chord")
    p = sub.add_parser("media", help="tap a media key"); p.add_argument("name")
    p = sub.add_parser("name", help="set the device label"); p.add_argument("label")

    args = ap.parse_args()

    host = args.host or discover()
    if not host:
        print("no deck found on the network; pass --host", file=sys.stderr)
        return 2

    deck = Deck(host, args.port)
    try:
        c = args.cmd
        if c == "status":
            print(json.dumps(ok(deck.call("hello")), indent=2))
        elif c == "profiles":
            for p_ in ok(deck.call("list_profiles"))["profiles"]:
                flag = " (built-in)" if p_.get("builtin") else ""
                print(f"{p_['slug']:12} {p_['name']:22} {p_['pages']} page(s){flag}")
        elif c == "get":
            print(json.dumps(ok(deck.call("get_profile", slug=args.slug))["profile"], indent=2))
        elif c == "put":
            with open(args.path) as f:
                ok(deck.call("put_profile", profile=json.load(f)))
            print("stored")
        elif c == "delete":
            ok(deck.call("del_profile", slug=args.slug)); print("deleted")
        elif c == "activate":
            ok(deck.call("set_active", slug=args.slug, page=args.page)); print("active")
        elif c == "press":
            kw = {"index": args.index, "state": args.state}
            if args.profile: kw["slug"] = args.profile
            if args.page is not None: kw["page"] = args.page
            ok(deck.call("press", **kw)); print("pressed")
        elif c == "macro":
            print(json.dumps(ok(deck.call("run_macro", macro=json.loads(args.json)))))
        elif c == "macro-file":
            with open(args.path) as f:
                print(json.dumps(ok(deck.call("run_macro", macro=json.load(f)))))
        elif c == "move":
            step = {"t": "move_abs", "x": args.x, "y": args.y,
                    "dur": args.dur, "ease": args.ease, "jitter": args.jitter}
            if args.display: step["display"] = args.display
            ok(deck.call("run_macro", macro={"name": "move", "steps": [step]}))
            print("moving")
        elif c == "nudge":
            ok(deck.call("run_macro", macro={"name": "nudge", "steps": [
                {"t": "move_rel", "dx": args.dx, "dy": args.dy, "dur": args.dur}]}))
            print("nudging")
        elif c == "drag":
            ok(deck.call("run_macro", macro={"name": "drag", "steps": [
                {"t": "drag", "from": {"x": args.x1, "y": args.y1},
                 "to": {"x": args.x2, "y": args.y2},
                 "btn": args.btn, "dur": args.dur, "ease": "in_out"}]}))
            print("dragging")
        elif c == "type":
            ok(deck.call("run_macro", macro={"name": "type", "steps": [
                {"t": "text", "s": args.text}]}))
            print("typing")
        elif c == "sd-status":
            print(json.dumps(deck.call("sd_status"), indent=2))
        elif c == "sd-take":
            r = ok(deck.call("sd_take"))
            print("taken" + ("" if r.get("filesystem") else " (no mountable filesystem)"))
        elif c == "sd-release":
            ok(deck.call("sd_release"))
            print("released")
        elif c == "sd-ls":
            r = ok(deck.call("sd_list", path=args.path))
            for e in sorted(r.get("entries", []),
                            key=lambda x: (not x["dir"], x["name"].lower())):
                print(f"{'d' if e['dir'] else '-'} {e['size']:>12}  {e['name']}")
            if r.get("truncated"):
                print("… truncated at 256 entries")
        elif c == "sd-get":
            import base64
            with open(args.local, "wb") as f:
                offset = 0
                while True:
                    r = ok(deck.call("sd_read", path=args.remote, offset=offset, max=24576))
                    data = base64.b64decode(r.get("data", ""))
                    f.write(data)
                    offset += len(data)
                    if r.get("eof", True):
                        break
            print(f"{offset} bytes -> {args.local}")
        elif c == "sd-put":
            import base64, os
            remote = args.remote or ("/" + os.path.basename(args.local))
            with open(args.local, "rb") as f:
                offset = 0
                while True:
                    chunk = f.read(24576)
                    if not chunk:
                        break
                    ok(deck.call("sd_write", path=remote, offset=offset,
                                 data=base64.b64encode(chunk).decode()))
                    offset += len(chunk)
            print(f"{offset} bytes -> {remote}")
        elif c == "sd-rm":
            ok(deck.call("sd_delete", path=args.remote))
            print("deleted")
        elif c == "key":
            ok(deck.call("run_macro", macro={"name": "key", "steps": [
                {"t": "tap", "keys": args.chord}]}))
            print("tapped")
        elif c == "media":
            ok(deck.call("run_macro", macro={"name": "media", "steps": [
                {"t": "consumer", "media": args.name}]}))
            print("tapped")
        elif c == "displays":
            print(json.dumps(ok(deck.call("get_displays"))["displays"], indent=2))
        elif c == "put-displays":
            with open(args.path) as f:
                ok(deck.call("put_displays", displays=json.load(f)))
            print("stored")
        elif c == "name":
            ok(deck.call("set_name", name=args.label)); print("renamed")
        elif c == "scan":
            for n in ok(deck.call("wifi_scan"))["nets"]:
                print(f"{n['rssi']:>4} dBm  {n['auth']:<5} {n['ssid']}")
        elif c == "stop":
            ok(deck.call("stop_macro")); print("stopped")
        elif c == "reboot":
            ok(deck.call("reboot")); print("rebooting")
        elif c == "factory-reset":
            ok(deck.call("factory_reset")); print("restored, rebooting")
        elif c == "watch":
            print(f"watching {host}; ctrl-c to stop")
            while True:
                print(json.dumps(deck.recv_json()))
        return 0
    finally:
        deck.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except (ConnectionError, OSError) as e:
        print(f"connection failed: {e}", file=sys.stderr)
        sys.exit(1)
