#!/usr/bin/env python3
"""
Fake OpenBeken RGBCW light for testing Shining's OpenBeken driver without flashing hardware.

Implements the subset of the Tasmota-style HTTP console Shining uses:
    GET /cm?cmnd=State
    GET /cm?cmnd=led_enableAll 1|0
    GET /cm?cmnd=Dimmer N          (also led_dimmer)
    GET /cm?cmnd=HSBColor h,s,b
    GET /cm?cmnd=CT n

    python3 tools/mock_openbeken.py --port 8080
Then set the light up in the app as an OpenBeken device with IP "<host>:8080".
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

state = {"on": False, "dimmer": 60, "white": True, "hue": 30, "sat": 0, "ct": 370}


def handle_command(cmnd: str):
    parts = cmnd.strip().split(None, 1)
    name = parts[0].lower()
    arg = parts[1].strip() if len(parts) > 1 else ""
    if name == "state":
        return None  # report full state
    elif name == "led_enableall":
        state["on"] = arg.startswith("1")
    elif name in ("dimmer", "led_dimmer"):
        if arg.isdigit():
            state["dimmer"] = max(1, min(100, int(arg)))
            state["on"] = True
    elif name == "hsbcolor":
        h, s, b = (arg.split(",") + ["", "", ""])[:3]
        state.update(white=False, hue=int(h or 0), sat=int(s or 100), dimmer=int(b or state["dimmer"]), on=True)
    elif name == "ct":
        state.update(white=True, sat=0, ct=int(arg or state["ct"]), on=True)
    return None


def report():
    s = state
    return {
        "POWER": "ON" if s["on"] else "OFF",
        "Dimmer": s["dimmer"],
        "HSBColor": f"{s['hue']},{0 if s['white'] else s['sat']},{s['dimmer']}",
        "CT": s["ct"],
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        if url.path != "/cm":
            self.send_error(404)
            return
        cmnd = parse_qs(url.query).get("cmnd", [""])[0]
        handle_command(cmnd)
        body = json.dumps(report()).encode()
        print(f"  {cmnd!r:40} -> {body.decode()}", flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    args = ap.parse_args()
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"mock OpenBeken light on :{args.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
