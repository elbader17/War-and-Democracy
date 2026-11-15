#!/usr/bin/env python3
"""
Generates PNG textures for the mod (without PIL).

    python3 tools/make_texture.py --out assets/modserver/textures/block/cog.png \
        --size 16 --style gear --palette purple
    python3 tools/make_texture.py --out assets/modserver/icon.png --size 32 --style gear

Styles: gear | plain
"""
import argparse
import math
import struct
import zlib

PALETTES = {
    "purple": {
        "body": (138, 104, 255),
        "body_alt": (176, 148, 255),
        "core": (64, 224, 224),
        "hole": (46, 40, 92),
        "bg": (28, 24, 48),
    },
    "cyan": {
        "body": (86, 214, 214),
        "body_alt": (150, 240, 240),
        "core": (255, 196, 84),
        "hole": (32, 58, 66),
        "bg": (20, 32, 40),
    },
}


def write_png(path, size, pixels):
    """pixels: flat list of RGBA bytes (size*size*4)."""
    raw = bytearray()
    stride = size * 4
    for y in range(size):
        raw.append(0)  # None filter
        raw += pixels[y * stride:(y + 1) * stride]
    if len(raw) != size * (1 + stride):
        raise ValueError(f"incorrect pixel buffer: {len(raw)} != {size * (1 + stride)}")

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff))

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    blob = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as fh:
        fh.write(blob)


def gear_pixels(size, palette):
    centre = (size - 1) / 2.0
    outer = size / 2.0 - 0.5
    inner = outer * 0.42
    ring = outer * 0.78
    px = bytearray()
    for y in range(size):
        for x in range(size):
            dx, dy = x - centre, y - centre
            r = math.hypot(dx, dy)
            angle = math.atan2(dy, dx)
            tooth = outer + (outer * 0.18) * math.cos(angle * 8)
            if inner <= r <= tooth:
                px += bytes(palette["body_alt"] if (x + y) % 7 == 0 else palette["body"]) + b"\xff"
            elif r < inner:
                px += bytes(palette["core"]) + b"\xff"
            elif inner <= r < ring:
                px += bytes(palette["hole"]) + b"\xff"
            else:
                px += bytes(palette["bg"]) + b"\xff"
    return px


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--size", type=int, default=16)
    parser.add_argument("--style", default="gear", choices=["gear"])
    parser.add_argument("--palette", default="purple", choices=sorted(PALETTES))
    args = parser.parse_args()

    pixels = gear_pixels(args.size, PALETTES[args.palette])
    write_png(args.out, args.size, pixels)
    print(f"Texture written to {args.out} ({args.size}x{args.size}, style {args.style})")


if __name__ == "__main__":
    main()
