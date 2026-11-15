#!/usr/bin/env python3
"""
Generates structure templates (.nbt) for GameTest tests.

Minecraft 1.21.1 does not include GameTest templates in its jars, so the mod
ships its own as a resource (data/<modid>/structure/<name>.nbt).

NBT format used (1.18+):
    TAG_Compound (root, unnamed)
      DataVersion : TAG_Int
      size        : TAG_List of TAG_Int [x,y,z]
      entities    : TAG_List (empty)
      palette     : TAG_List of TAG_Compound { Name: TAG_String }
      blocks      : TAG_List of TAG_Compound { pos: TAG_List[3 int], state: TAG_Int }

Usage:
    python3 tools/make_structure.py --out src/main/resources/data/modserver/structure/empty.nbt
    python3 tools/make_structure.py --out ... --size 5 5 5 --block minecraft:polished_andesite
"""
import argparse
import gzip
import struct

# DataVersion of Minecraft 1.21.1
DATA_VERSION_1_21_1 = 3955


# --------------------------------------------------------------------- NBT
def _named(tag_type: int, name: str, payload: bytes) -> bytes:
    """Named tag: type + UTF-8 name + payload."""
    encoded = name.encode("utf-8")
    return struct.pack(">B", tag_type) + struct.pack(">H", len(encoded)) + encoded + payload


def _compound(*children: bytes) -> bytes:
    """Body of a TAG_Compound: named tags + TAG_End."""
    return b"".join(children) + struct.pack(">B", 0)


def _string_payload(value: str) -> bytes:
    """Payload of a TAG_String (the UTF-8 bytes plus their length)."""
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def _int_list(values) -> bytes:
    """Payload of a TAG_List of TAG_Int: type + element count + data."""
    payload = b"".join(struct.pack(">i", v) for v in values)
    return struct.pack(">B", 3) + struct.pack(">i", len(values)) + payload


def _list_of_compounds(items) -> bytes:
    """Payload of a TAG_List of TAG_Compound."""
    payload = b"".join(items)
    return struct.pack(">B", 10) + struct.pack(">i", len(items)) + payload


def _empty_compound_list() -> bytes:
    return struct.pack(">B", 10) + struct.pack(">i", 0)


# ------------------------------------------------------------- template
def build_template(size, block_name: str) -> bytes:
    sx, sy, sz = size

    # Palette: index 0 = air, index 1 = filler block.
    # The elements of an NBT list go WITHOUT a name (only the compound).
    palette = _list_of_compounds([
        _compound(_named(8, "Name", _string_payload("minecraft:air"))),
        _compound(_named(8, "Name", _string_payload(block_name))),
    ])

    blocks = []
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                # block floor on the bottom layer, air everywhere else
                state = 1 if y == 0 else 0
                blocks.append(_compound(
                    _named(9, "pos", _int_list([x, y, z])),
                    _named(3, "state", struct.pack(">i", state)),
                ))

    root = _compound(
        _named(3, "DataVersion", struct.pack(">i", DATA_VERSION_1_21_1)),
        _named(9, "size", _int_list([sx, sy, sz])),
        _named(9, "entities", _empty_compound_list()),
        _named(9, "palette", palette),
        _named(9, "blocks", _list_of_compounds(blocks)),
    )
    # The root is an unnamed TAG_Compound.
    return struct.pack(">B", 10) + root


# ------------------------------------------------------------ validation
def validate(data: bytes) -> None:
    """Checks that the generated NBT can be read whole and is consistent."""
    pos = 0

    def read(n):
        nonlocal pos
        if pos + n > len(data):
            raise ValueError("truncated NBT")
        chunk = data[pos:pos + n]
        pos += n
        return chunk

    def read_payload(tag_type):
        if tag_type == 1:
            read(1)
        elif tag_type == 2:
            read(2)
        elif tag_type == 3:
            read(4)
        elif tag_type == 4:
            read(8)
        elif tag_type == 5:
            read(4)
        elif tag_type == 6:
            read(8)
        elif tag_type == 7:
            read(8)
        elif tag_type == 8:
            read(struct.unpack(">H", read(2))[0])
        elif tag_type == 9:
            element = read(1)[0]
            count = struct.unpack(">i", read(4))[0]
            for _ in range(count):
                read_payload(element)
        elif tag_type == 10:
            while True:
                child = read(1)[0]
                if child == 0:
                    break
                name_length = struct.unpack(">H", read(2))[0]
                read(name_length)  # name contents
                read_payload(child)
        elif tag_type == 11:
            count = struct.unpack(">i", read(4))[0]
            for _ in range(count):
                read(4)
        elif tag_type == 12:
            count = struct.unpack(">i", read(4))[0]
            for _ in range(count):
                read(8)
        else:
            raise ValueError(f"unknown tag type: {tag_type}")

    root = read(1)[0]
    if root != 10:
        raise ValueError(f"the root should be TAG_Compound, it is {root}")
    read_payload(root)
    if pos != len(data):
        raise ValueError(f"{len(data) - pos} extra bytes after the root")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--size", nargs=3, type=int, default=[3, 3, 3])
    parser.add_argument("--block", default="minecraft:polished_andesite")
    args = parser.parse_args()

    data = build_template(tuple(args.size), args.block)
    validate(data)
    with gzip.open(args.out, "wb") as fh:
        fh.write(data)
    print(f"Template written to {args.out} "
          f"({args.size[0]}x{args.size[1]}x{args.size[2]}, floor of {args.block}) [valid NBT]")


if __name__ == "__main__":
    main()
