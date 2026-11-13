#!/usr/bin/env python3
"""
Fake OpenBeken RGBCW light for testing Shining's OpenBeken driver without flashing hardware.

Implements the subset of the Tasmota-style HTTP console Shining uses:
    GET /cm?cmnd=Status 11      (state as real OpenBeken: {"StatusSTS": {...}})
    GET /cm?cmnd=led_enableAll 1|0
    GET /cm?cmnd=Dimmer N          (also led_dimmer)
    GET /cm?cmnd=HSBColor h,s,b
    GET /cm?cmnd=CT n

    python3 tools/mock_openbeken.py --port 8080
Then set the light up in the app as an OpenBeken device with IP "<host>:8080".
"""
import argparse
import base64
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

state = {"on": False, "dimmer": 100, "white": True, "hue": 30, "sat": 0, "ct": 370, "vbri": 60}
# Real OpenBeken keeps the master "dimmer" separate from the colour value ("vbri" = HSBColor V).
AUTH = None  # when set to "user:pass", /cm requires HTTP Basic auth (like real OpenBeken)


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
        state.update(white=False, hue=int(h or 0), sat=int(s or 100), vbri=int(b or state["vbri"]), on=True)  # colour value, NOT master dimmer
    elif name == "ct":
        state.update(white=True, ct=int(arg or state["ct"]), on=True)  # keep sat: real OB reports it even in white mode
    return None


def report():
    s = state
    return {
        "POWER": "ON" if s["on"] else "OFF",
        "Dimmer": s["dimmer"],
        "HSBColor": f"{s['hue']},{s['sat']},{s['vbri']}",
        "CT": s["ct"],
        # Raw output channels R,G,B,C,W: RGB off in white mode, white channels off in colour mode.
        "Color": (f"0,0,0,{s['dimmer']},{s['dimmer']}" if s["white"]
                  else f"{max(1, s['vbri'] * 2)},{max(1, s['sat'])},1,0,0"),
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        if url.path != "/cm":
            self.send_error(404)
            return
        if AUTH is not None:
            expected = "Basic " + base64.b64encode(AUTH.encode()).decode()
            if self.headers.get("Authorization") != expected:
                self.send_response(401)
                self.send_header("WWW-Authenticate", 'Basic realm="OpenBeken HTTP Server"')
                self.send_header("Content-Length", "0")
                self.end_headers()
                print(f"  401 (missing/wrong auth)", flush=True)
                return
        cmnd = parse_qs(url.query).get("cmnd", [""])[0]
        handle_command(cmnd)
        name = cmnd.strip().split(None, 1)[0].lower() if cmnd.strip() else ""
        if name == "status":
            body = json.dumps({"StatusSTS": report()}).encode()
        elif name == "state":
            body = b""  # real OpenBeken returns nothing for "State"
        else:
            body = json.dumps(report()).encode()
        print(f"  {cmnd!r:40} -> {body.decode() or '(empty)'}", flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def main():
    global AUTH
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--password", default="", help="if set, require HTTP Basic auth admin:<password>")
    args = ap.parse_args()
    if args.password:
        AUTH = f"admin:{args.password}"
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"mock OpenBeken light on :{args.port}" + (f" (auth admin:{args.password})" if AUTH else ""), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
