"""
Converts Path of Building (PoE2) passive tree data into Android-friendly assets.

Reads   : PathOfBuilding-PoE2/src/TreeData/<version>/tree.json + *.dds.zst texture arrays + orbit PNGs
Writes  : app/src/main/assets/tree/
            tree.json      - minified copy of the tree data
            sprites.json   - { spriteName: {"f": file, "w": origWidth, "h": origHeight, "sw"/"sh": stored size} }
            atlas.json     - node art packed into atlas_<px>.webp pages for batched drawing
            s/*.webp|png   - one image per sprite (texture array layer, mip 0)

Requires Python 3.14+ (stdlib compression.zstd) and Pillow.
Usage   : python tools/build_assets.py [--pob ../PathOfBuilding-PoE2] [--version 0_5]
"""
import argparse
import json
import math
import os
import shutil
import struct
import sys
from compression import zstd

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)

# DXGI formats we need to decode
DXGI_BC1 = (70, 71, 72)
DXGI_BC7 = (97, 98, 99)
DXGI_RGBA = (28, 29)

# Texture files we don't need (oils, greyed-out icons, older jewel radius art)
SKIP_PREFIXES = ("skills-disabled_", "oils_", "legion_1024", "legion_564")

# Path of Building's own images drawn on the tree: jewel radius rings (PassiveTreeView)
POB_ASSETS = ("ShadedOuterRing", "ShadedOuterRingFlipped", "ShadedInnerRing", "ShadedInnerRingFlipped")

# Transparent gutter added to the right/bottom of each layer in newer texture arrays
PADDING = 8

# Cap the stored resolution of very large images (memory on phones).
# Drawing sizes come from the tree data, so downscaling only affects sharpness.
MAX_DIM = {
    "ascendancy-background_4": 2048,  # BGTree / BGTreeActive (class centre art)
    "ascendancy-background_1": 1024,  # class + ascendancy backgrounds
    "mastery-active-effect_": 384,
    "legion_572_": 512,   # conquering jewel radius circles
    "legion_1032_": 512,
}


def read_dds_layers(path):
    raw = zstd.decompress(open(path, "rb").read())
    if raw[:4] != b"DDS ":
        raise ValueError(f"{path}: not a DDS")
    h = struct.unpack("<31I", raw[4:128])
    height, width, mips = h[2], h[3], max(1, h[6])
    fourcc = raw[84:88]
    offset = 128
    arr = 1
    if fourcc == b"DX10":
        dxgi, _dim, _misc, arr, _misc2 = struct.unpack("<5I", raw[128:148])
        offset = 148
    else:
        raise ValueError(f"{path}: unsupported non-DX10 dds")

    if dxgi in DXGI_BC1:
        block, codec = 8, 1
    elif dxgi in DXGI_BC7:
        block, codec = 16, 7
    elif dxgi in DXGI_RGBA:
        block, codec = None, None
    else:
        raise ValueError(f"{path}: unsupported dxgi {dxgi}")

    def mip_size(w, h):
        if block is None:
            return w * h * 4
        return max(1, math.ceil(w / 4)) * max(1, math.ceil(h / 4)) * block

    layer_size = 0
    w, hh = width, height
    for _ in range(mips):
        layer_size += mip_size(w, hh)
        w, hh = max(1, w // 2), max(1, hh // 2)
    mip0 = mip_size(width, height)

    def get(layer_index):
        start = offset + layer_index * layer_size
        data = raw[start:start + mip0]
        if block is None:
            return Image.frombytes("RGBA", (width, height), data)
        # Pillow's BCn decoder works on whole 4x4 blocks
        bw, bh = math.ceil(width / 4) * 4, math.ceil(height / 4) * 4
        img = Image.frombytes("RGBA", (bw, bh), data, "bcn", codec)
        if (bw, bh) != (width, height):
            img = img.crop((0, 0, width, height))
        return img

    return width, height, arr, get


def max_dim_for(file_name):
    for prefix, dim in MAX_DIM.items():
        if file_name.startswith(prefix):
            return dim
    return None


def save_image(img, out_path_noext, lossless):
    # WebP keeps alpha and is much smaller than PNG for the big painted backgrounds
    path = out_path_noext + ".webp"
    if lossless:
        img.save(path, "WEBP", lossless=True, quality=80, method=4)
    else:
        img.save(path, "WEBP", quality=88, method=4)
    return path


# Node art is also packed into small texture atlases so that thousands of nodes can be
# drawn in a few batched draw calls when zoomed out. Each level holds every node sprite
# scaled to fit a square of that many pixels.
ATLAS_LEVELS = (12, 32)
ATLAS_MAX_WIDTH = 2048


def node_sprite_names(tree):
    names = {"AscendancyMiddle"}
    for data in tree["nodeOverlay"].values():
        names.update(data.values())
    for node in tree["nodes"].values():
        if node.get("isOnlyImage"):
            continue
        if node.get("icon"):
            names.add(node["icon"])
        names.update((node.get("nodeOverlay") or {}).values())
        options = node.get("options") or []
        for o in options.values() if isinstance(options, dict) else options:
            if o.get("icon"):
                names.add(o["icon"])
            names.update((o.get("nodeOverlay") or {}).values())
    return names


def build_atlases(sources, out):
    names = sorted(sources)
    atlas = {"levels": {}}
    for level in ATLAS_LEVELS:
        pad = 1 if level <= 16 else 2
        cell = level + 2 * pad
        cols = ATLAS_MAX_WIDTH // cell
        rows = math.ceil(len(names) / cols)
        page = Image.new("RGBA", (cols * cell, rows * cell), (0, 0, 0, 0))
        rects = {}
        for i, name in enumerate(names):
            img = sources[name]
            scale = level / max(img.width, img.height)
            w, h = max(1, round(img.width * scale)), max(1, round(img.height * scale))
            small = img.resize((w, h), Image.LANCZOS)
            x = (i % cols) * cell + pad + (level - w) // 2
            y = (i // cols) * cell + pad + (level - h) // 2
            page.paste(small, (x, y))
            rects[name] = [x, y, w, h]
        file_name = f"atlas_{level}.webp"
        page.save(os.path.join(out, file_name), "WEBP", lossless=True, quality=80, method=4)
        atlas["levels"][str(level)] = {"file": file_name, "width": page.width, "height": page.height, "rects": rects}
        print(f"atlas {level}px: {len(names)} sprites, {page.width}x{page.height}")
    with open(os.path.join(out, "atlas.json"), "w", encoding="utf-8") as f:
        json.dump(atlas, f, separators=(",", ":"), ensure_ascii=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pob", default=os.path.join(os.path.dirname(PROJECT), "PathOfBuilding-PoE2"))
    ap.add_argument("--version", default="0_5")
    args = ap.parse_args()

    src = os.path.join(args.pob, "src", "TreeData", args.version)
    out = os.path.join(PROJECT, "app", "src", "main", "assets", "tree")
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, "s"))

    tree = json.load(open(os.path.join(src, "tree.json"), encoding="utf-8"))
    tree["pobTreeVersion"] = args.version
    with open(os.path.join(out, "tree.json"), "w", encoding="utf-8") as f:
        json.dump(tree, f, separators=(",", ":"), ensure_ascii=False)

    sprites = {}
    counter = 0
    node_art = node_sprite_names(tree)
    atlas_sources = {}  # name -> full resolution PIL image

    # Texture arrays referenced by ddsCoords
    for file_name, coords in sorted(tree["ddsCoords"].items()):
        if file_name.startswith(SKIP_PREFIXES):
            continue
        path = os.path.join(src, file_name)
        if not os.path.exists(path):
            print(f"  missing texture {file_name}", file=sys.stderr)
            continue
        width, height, arr, get = read_dds_layers(path)
        wanted = {}  # layer -> sprite names
        for name, index in coords.items():
            if name in sprites and sprites[name]["w"] >= width:
                continue  # prefer the largest variant of an icon
            layer = index - 1
            if layer < 0 or layer >= arr:
                print(f"  bad layer {index} for {name} in {file_name}", file=sys.stderr)
                continue
            wanted.setdefault(layer, []).append(name)
        if not wanted:
            continue
        layers = {layer: get(layer) for layer in sorted(wanted)}

        # Newer texture arrays pad every layer with transparent pixels on the right and bottom.
        # Crop the padding so each sprite is centred where the game draws it.
        boxes = [b for b in (im.getchannel("A").getbbox() for im in layers.values()) if b]
        art_w, art_h = width, height
        if boxes and width > 4 * PADDING and max(b[2] for b in boxes) <= width - PADDING:
            art_w = width - PADDING
        if boxes and height > 4 * PADDING and max(b[3] for b in boxes) <= height - PADDING:
            art_h = height - PADDING

        cap = max_dim_for(file_name)
        lossless = art_w * art_h <= 256 * 256
        for layer, names in wanted.items():
            img = layers[layer]
            if (art_w, art_h) != (width, height):
                img = img.crop((0, 0, art_w, art_h))
            for name in names:
                if name in node_art:
                    atlas_sources[name] = img
            stored = img
            if cap and max(art_w, art_h) > cap:
                s = cap / max(art_w, art_h)
                stored = img.resize((max(1, round(art_w * s)), max(1, round(art_h * s))), Image.LANCZOS)
            counter += 1
            rel = os.path.relpath(save_image(stored, os.path.join(out, f"s/{counter}"), lossless), out).replace("\\", "/")
            for name in names:
                sprites[name] = {"f": rel, "w": art_w, "h": art_h, "sw": stored.width, "sh": stored.height}
        cropped = "" if (art_w, art_h) == (width, height) else f", cropped to {art_w}x{art_h}"
        print(f"{file_name}: {len(wanted)} images{cropped}")

    # Plain PNG assets: only the straight connector art is needed. The app draws curved connectors
    # as ribbons textured with the same line art instead of PoB's large quarter-circle images.
    for name, files in tree.get("assets", {}).items():
        if "LineConnector" not in name:
            continue
        png = files[0]
        path = os.path.join(src, png)
        if not os.path.exists(path):
            print(f"  missing asset {png}", file=sys.stderr)
            continue
        img = Image.open(path)
        counter += 1
        rel = f"s/{counter}.png"
        shutil.copyfile(path, os.path.join(out, rel))
        sprites[name] = {"f": rel, "w": img.width, "h": img.height, "sw": img.width, "sh": img.height}

    for name in POB_ASSETS:
        img = Image.open(os.path.join(args.pob, "src", "Assets", name + ".png")).convert("RGBA")
        counter += 1
        rel = os.path.relpath(save_image(img, os.path.join(out, f"s/{counter}"), False), out).replace("\\", "/")
        sprites[name] = {"f": rel, "w": img.width, "h": img.height, "sw": img.width, "sh": img.height}

    with open(os.path.join(out, "sprites.json"), "w", encoding="utf-8") as f:
        json.dump(sprites, f, separators=(",", ":"), ensure_ascii=False)

    build_atlases(atlas_sources, out)

    # Report node art that we could not resolve
    missing = set()
    for node in tree["nodes"].values():
        for key in ("icon", "activeEffectImage"):
            if node.get(key) and node[key] not in sprites:
                missing.add(node[key])
        for state in (node.get("nodeOverlay") or {}).values():
            if state not in sprites:
                missing.add(state)
    for data in tree["nodeOverlay"].values():
        for state in data.values():
            if state not in sprites:
                missing.add(state)
    if missing:
        print(f"{len(missing)} sprites referenced by nodes are missing:", file=sys.stderr)
        for m in sorted(missing)[:30]:
            print("   ", m, file=sys.stderr)

    total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(out) for f in fs)
    print(f"{len(sprites)} sprites, {counter} files, {total / 1e6:.1f} MB written to {out}")


if __name__ == "__main__":
    main()
