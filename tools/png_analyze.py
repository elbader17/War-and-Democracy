#!/usr/bin/env python3
"""Analyses a PNG screenshot and counts the pixels per color in a region.

Own PNG decoder (only 8-bit RGB/RGBA non-interlaced PNG) with the Paeth filter
properly implemented: p = a + b - c (NOT a + b - b).
"""
import sys
import zlib
from collections import Counter


def read_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not a PNG'
    pos = 8
    idat = b''
    w = h = bitdepth = colortype = None
    interlace = 0
    while pos < len(data):
        length = int.from_bytes(data[pos:pos + 4], 'big')
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b'IHDR':
            w = int.from_bytes(chunk[0:4], 'big')
            h = int.from_bytes(chunk[4:8], 'big')
            bitdepth = chunk[8]
            colortype = chunk[9]
            interlace = chunk[12]
        elif ctype == b'IDAT':
            idat += chunk
        elif ctype == b'IEND':
            break
        pos += 12 + length
    assert bitdepth == 8, f'bitdepth {bitdepth} not supported'
    assert interlace == 0, 'interlaced PNG not supported'
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    raw = zlib.decompress(idat)
    stride = w * channels
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if ft == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ft == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                pa = abs(b - c)
                pb = abs(a - c)
                pc = abs(a + b - 2 * c)
                if pa <= pb and pa <= pc:
                    pr = a
                elif pb <= pc:
                    pr = b
                else:
                    pr = c
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, channels, out


def main():
    path = sys.argv[1]
    x0, y0, x1, y1 = (int(v) for v in sys.argv[2:6]) if len(sys.argv) > 5 else (0, 0, 10 ** 9, 10 ** 9)
    w, h, channels, pix = read_png(path)
    counter = Counter()
    for y in range(y0, min(y1, h)):
        for x in range(x0, min(x1, w)):
            o = (y * w + x) * channels
            if channels >= 3:
                counter[(pix[o], pix[o + 1], pix[o + 2])] += 1
            else:
                counter[(pix[o], pix[o], pix[o])] += 1
    total = sum(counter.values())
    print(f'{path} region=({x0},{y0})-({x1},{y1}) pixels={total} colors={len(counter)}')
    for color, count in counter.most_common(20):
        print(f'  #{color[0]:02X}{color[1]:02X}{color[2]:02X}  {count}')


if __name__ == '__main__':
    main()
