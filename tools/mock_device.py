#!/usr/bin/env python3
"""
Fake Tuya RGB light for testing the app without real hardware.

Built on tinytuya's own framing/crypto helpers, so it is an independent
reference implementation to test the Kotlin client against.

    pip install tinytuya
    python3 tools/mock_device.py --version 3.3 --port 6668

Then point the app (or the JVM integration tests) at this machine's IP with
device id / local key printed below.
"""
import argparse
import hashlib
import hmac
import json
import os
import socket
import struct
import time

from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core.message_helper import TuyaMessage, pack_message, unpack_message, parse_header
from tinytuya.core import header as H

DEVICE_ID = "bf0123456789abcdefgh"
LOCAL_KEY = b"0123456789abcdef"
UDP_KEY = hashlib.md5(b"yGAdlopoPVldABfn").digest()

SESS_KEY_NEG_START, SESS_KEY_NEG_RESP, SESS_KEY_NEG_FINISH = 3, 4, 5
CONTROL, STATUS, HEART_BEAT, DP_QUERY, CONTROL_NEW, DP_QUERY_NEW = 7, 8, 9, 10, 13, 16

dps = {"20": False, "21": "white", "22": 500, "23": 300, "24": "000003e803e8", "25": "", "26": 0}


def recv_frame(conn):
    buf = b""
    while len(buf) < 18:
        chunk = conn.recv(4096)
        if not chunk:
            return None
        buf += chunk
    hdr = parse_header(buf)
    total = 16 + hdr.length if hdr.prefix == H.PREFIX_55AA_VALUE else 18 + hdr.length + 4
    while len(buf) < total:
        chunk = conn.recv(4096)
        if not chunk:
            return None
        buf += chunk
    assert len(buf) == total, "mock expects one frame per recv burst"
    return buf


class Session:
    def __init__(self, version):
        self.version = version
        self.key = LOCAL_KEY  # becomes session key for 3.4/3.5
        self.seq = 100
        self.vhdr = version.encode() + b"\0" * 12

    # --- client -> device ---
    def unpack(self, raw):
        if self.version == "3.5":
            msg = unpack_message(raw, hmac_key=self.key, no_retcode=True)
            payload = msg.payload
        elif self.version == "3.4":
            msg = unpack_message(raw, hmac_key=self.key, no_retcode=True)
            payload = AESCipher(self.key).decrypt(msg.payload, False, decode_text=False)
        else:
            msg = unpack_message(raw, no_retcode=True)
            payload = msg.payload
            if payload.startswith(b"3.3"):
                payload = payload[15:]
            payload = AESCipher(self.key).decrypt(payload, False, decode_text=False)
        if payload.startswith(self.vhdr[:3]):
            payload = payload[15:]
        return msg.cmd, payload

    # --- device -> client ---
    def pack(self, cmd, payload, header=False):
        self.seq += 1
        if self.version == "3.5":
            if header:
                payload = self.vhdr + payload
            msg = TuyaMessage(self.seq, cmd, 0, payload, 0, True, H.PREFIX_6699_VALUE, True)
            return pack_message(msg, hmac_key=self.key)
        if self.version == "3.4":
            if header:
                payload = self.vhdr + payload
            if payload:
                payload = AESCipher(self.key).encrypt(payload, False)
            msg = TuyaMessage(self.seq, cmd, 0, struct.pack(">I", 0) + payload, 0, True, H.PREFIX_55AA_VALUE)
            return pack_message(msg, hmac_key=self.key)
        if payload:
            payload = AESCipher(self.key).encrypt(payload, False)
            if header:
                payload = self.vhdr + payload
        msg = TuyaMessage(self.seq, cmd, 0, struct.pack(">I", 0) + payload, 0, True, H.PREFIX_55AA_VALUE)
        return pack_message(msg)

    def handle(self, conn, raw):
        cmd, payload = self.unpack(raw)
        if cmd == SESS_KEY_NEG_START:
            self.local_nonce = payload[:16]
            self.remote_nonce = os.urandom(16)
            body = self.remote_nonce + hmac.new(LOCAL_KEY, self.local_nonce, hashlib.sha256).digest()
            conn.sendall(self.pack(SESS_KEY_NEG_RESP, body))
        elif cmd == SESS_KEY_NEG_FINISH:
            expected = hmac.new(LOCAL_KEY, self.remote_nonce, hashlib.sha256).digest()
            if payload[:32] != expected:
                print("!! bad NEG_FINISH hmac")
                return False
            mixed = bytes(a ^ b for a, b in zip(self.local_nonce, self.remote_nonce))
            cipher = AESCipher(LOCAL_KEY)
            if self.version == "3.4":
                self.key = cipher.encrypt(mixed, False, pad=False)
            else:
                self.key = cipher.encrypt(mixed, use_base64=False, pad=False, iv=self.local_nonce[:12])[12:28]
            print("   session key established")
        elif cmd in (DP_QUERY, DP_QUERY_NEW):
            req = json.loads(payload or b"{}")
            print("   query", req)
            body = json.dumps({"devId": DEVICE_ID, "dps": dps, "t": int(time.time())}).encode()
            conn.sendall(self.pack(cmd, body))
        elif cmd in (CONTROL, CONTROL_NEW):
            req = json.loads(payload)
            new = req.get("dps") or req.get("data", {}).get("dps", {})
            print("   set", new)
            dps.update(new)
            conn.sendall(self.pack(cmd, b""))  # ack
            if self.version == "3.3":
                push = {"devId": DEVICE_ID, "dps": new, "t": int(time.time())}
            else:
                push = {"protocol": 4, "t": int(time.time()), "data": {"dps": new}}
            conn.sendall(self.pack(STATUS, json.dumps(push).encode(), header=True))
        elif cmd == HEART_BEAT:
            conn.sendall(self.pack(HEART_BEAT, b""))
        else:
            print("   unhandled cmd", cmd)
        return True


def broadcast_packet(version, ip):
    """Builds the UDP discovery broadcast a real device would send."""
    body = json.dumps({"ip": ip, "gwId": DEVICE_ID, "active": 2, "ability": 0, "mode": 0,
                       "encrypt": True, "productKey": "keymockproduct", "version": version}).encode()
    if version == "3.5":
        msg = TuyaMessage(0, 0x13, 0, body, 0, True, H.PREFIX_6699_VALUE, True)
        return pack_message(msg, hmac_key=UDP_KEY), 7000
    enc = AESCipher(UDP_KEY).encrypt(body, False)
    msg = TuyaMessage(0, 0x13, 0, struct.pack(">I", 0) + enc, 0, True, H.PREFIX_55AA_VALUE)
    return pack_message(msg), 6667


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="3.3", choices=["3.3", "3.4", "3.5"])
    ap.add_argument("--port", type=int, default=6668)
    ap.add_argument("--print-broadcast", metavar="IP", help="print a discovery packet (hex) and exit")
    args = ap.parse_args()

    if args.print_broadcast:
        pkt, port = broadcast_packet(args.version, args.print_broadcast)
        print(port, pkt.hex())
        return

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen(1)
    print(f"mock Tuya {args.version} light on :{args.port}  id={DEVICE_ID} key={LOCAL_KEY.decode()}", flush=True)
    while True:
        conn, addr = srv.accept()
        conn.settimeout(30)  # like real devices: drop clients that go quiet
        print("client", addr, flush=True)
        session = Session(args.version)
        try:
            while True:
                raw = recv_frame(conn)
                if raw is None:
                    break
                if not session.handle(conn, raw):
                    break
        except Exception as e:  # keep serving
            print("!! error:", repr(e), flush=True)
        finally:
            conn.close()
            print("client closed", flush=True)


if __name__ == "__main__":
    main()
