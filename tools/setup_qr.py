#!/usr/bin/env python3
"""
Turn tinytuya's wizard output into a QR code the Shining app understands.

    python3 -m tinytuya wizard          # once: creates devices.json (+ snapshot.json)
    python3 tools/setup_qr.py           # prints a QR; scan it with the phone camera

Options: --name "Desk strip" to pick a device, --ip / --version to override,
--png qr.png to save the QR as an image.
Keep devices.json private: it contains your local keys.
"""
import argparse
import json
import os
import sys
from urllib.parse import quote


def load(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        data = json.load(f)
    return data.get("devices", data) if isinstance(data, dict) else data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--devices", default="devices.json")
    ap.add_argument("--snapshot", default="snapshot.json")
    ap.add_argument("--name", help="device name (substring match) if you have several")
    ap.add_argument("--ip")
    ap.add_argument("--version")
    ap.add_argument("--png", metavar="FILE", help="also save the QR as an image (needs: pip install 'qrcode[pil]')")
    args = ap.parse_args()

    devices = load(args.devices)
    if not devices:
        sys.exit(f"No devices in {args.devices}. Run `python3 -m tinytuya wizard` first.")
    if args.name:
        devices = [d for d in devices if args.name.lower() in d.get("name", "").lower()]
    lights = [d for d in devices if d.get("category") in ("dj", "dd", "fwd", "dc", "xdd")] or devices
    if len(lights) > 1 and not args.name:
        print("Several devices found; pick one with --name:")
        for d in lights:
            print(f"  {d.get('name')!r}  ({d.get('product_name', '')})")
        sys.exit(1)
    dev = lights[0]

    snap = {d.get("id"): d for d in load(args.snapshot)}.get(dev["id"], {})
    ip = args.ip or dev.get("ip") or snap.get("ip")
    version = args.version or str(dev.get("version") or snap.get("ver") or snap.get("version") or "3.3")
    if not ip:
        sys.exit("Device IP unknown. Pass --ip (find it in your router, or run `python3 -m tinytuya scan`).")

    params = {"name": dev.get("name") or "Lightstrip", "id": dev["id"], "ip": ip, "key": dev["key"], "version": version}
    link = "shining://setup?" + "&".join(f"{k}={quote(str(v), safe='')}" for k, v in params.items())

    print(f"\n{params['name']}  ip={ip}  version={version}\n")
    try:
        import qrcode  # pip install qrcode
        qr = qrcode.QRCode(border=2)
        qr.add_data(link)
        qr.print_ascii(invert=True)
        if args.png:
            qrcode.make(link, border=4, box_size=12).save(args.png)
            print(f"\nQR image saved to {args.png} (it contains your local key; delete it after scanning)")
    except ImportError:
        print("(pip install qrcode to get a scannable QR code here)")
    print("\nSetup link (or copy this to the phone and use 'Paste setup link'):\n" + link + "\n")


if __name__ == "__main__":
    main()
