#!/usr/bin/env python3
"""
Minimal RCON client for Minecraft (standard library only).

    python3 tools/rcon.py "say hola"
    python3 tools/rcon.py --list "list"

RCON protocol: length | id | type | body\\0
  type 3 = login, type 2 = command, type 0 = response
"""
import argparse
import os
import socket
import struct
import sys

SERVERDATA_AUTH = 3
SERVERDATA_COMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0
SERVERDATA_AUTH_RESPONSE = 2


def _pack(request_id: int, packet_type: int, body: str) -> bytes:
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _recv_exact(sock, size: int) -> bytes:
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("the RCON server closed the connection")
        data += chunk
    return data


def _recv_packet(sock):
    length = struct.unpack("<i", _recv_exact(sock, 4))[0]
    payload = _recv_exact(sock, length)
    request_id, packet_type = struct.unpack("<ii", payload[:8])
    body = payload[8:-2].decode("utf-8", "replace")
    return request_id, packet_type, body


def _read_one(sock, request_id):
    """Reads one packet from the server, ignoring responses with another id."""
    while True:
        rid, packet_type, body = _recv_packet(sock)
        if rid == request_id:
            return packet_type, body
        if rid == -1:
            raise PermissionError("RCON: authentication failed (wrong password)")


def rcon(host: str, port: int, password: str, command: str, timeout: float = 15.0) -> str:
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        # Login: the server answers with a single packet (type 2, empty body).
        sock.sendall(_pack(1, SERVERDATA_AUTH, password))
        _read_one(sock, 1)
        # Command: the server answers with a type 0 packet holding the text.
        sock.sendall(_pack(2, SERVERDATA_COMMAND, command))
        _, body = _read_one(sock, 2)
        return body.strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", nargs="+")
    parser.add_argument("--host", default=os.environ.get("MODSERVER_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("MODSERVER_RCON_PORT", "25575")))
    parser.add_argument("--password", default=os.environ.get("MODSERVER_RCON_PASSWORD", "modserver"))
    args = parser.parse_args()

    command = " ".join(args.command)
    try:
        out = rcon(args.host, args.port, args.password, command)
    except Exception as exc:  # noqa: BLE001 - we want a clear message on the console
        print(f"ERROR RCON: {exc}", file=sys.stderr)
        return 1
    if out:
        print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
