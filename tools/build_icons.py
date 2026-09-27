"""
Picks the icons of item bases, uniques, gems and runes from the poe2db download for the app.

Reads   : ../poe2db-assets/manifest.json + images/ (from poe2db-assets/fetch_poe2db_assets.py)
          app/src/main/assets/pob/Data (the bundled Path of Building: its bases and uniques)
Writes  : app/src/main/assets/icons/
            index.json  - { "bases" | "uniques" | "gems" | "runes": { folded name: file } }
            <game art path, lower case>.webp

Names are folded (accents removed, lower case) because poe2db writes "Mórrigan's Insight" where
Path of Building writes "Morrigan's Insight"; ui/GameIcons.kt folds them the same way. Item art
larger than --max-px on its longest side is scaled down (it is shown at list-row and tooltip
size). Every gem-typed icon is kept, since the Skills screen also shows skills granted by items
and passives. Runes (and soul cores, talismans, idols: what goes in an item's sockets) are the
ones of Path of Building's ModRunes.

Requires Pillow.
Usage   : python tools/build_icons.py [--manifest ../poe2db-assets/manifest.json] [--max-px 240]
"""
import argparse
import collections
import glob
import json
import os
import re
import shutil
import sys
import unicodedata

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
OUT = os.path.join(PROJECT, "app", "src", "main", "assets", "icons")
POB_DATA = os.path.join(PROJECT, "app", "src", "main", "assets", "pob", "Data")


def fold(name):
    name = name.replace("’", "'")
    return unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower().strip()


def pob_bases():
    names = set()
    for f in glob.glob(os.path.join(POB_DATA, "Bases", "*.lua")):
        with open(f, encoding="utf-8") as fh:
            names.update(re.findall(r'itemBases\["(.*?)"\]', fh.read()))
    return names


def pob_uniques():
    names = set()
    for f in glob.glob(os.path.join(POB_DATA, "Uniques", "*.lua")):
        with open(f, encoding="utf-8") as fh:
            names.update(n.strip() for n in re.findall(r"\[\[\s*\n?(.*?)\n", fh.read()))
    return names


def pob_runes():
    with open(os.path.join(POB_DATA, "ModRunes.lua"), encoding="utf-8") as fh:
        return set(re.findall(r'^\t\["(.*?)"\] = \{', fh.read(), re.M))


def choose(entries, item_art):
    """The icon for a name: item art first (for items), then the one most entries use, then an
    entry of a listing page over an item's own page."""
    counts = collections.Counter(e["image"] for e in entries)
    def score(e):
        art = "/2ditems/" in e["image"].lower()
        return (art if item_art else True, counts[e["image"]], e["category"] != "Item_page")
    return max(entries, key=score)["image"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default=os.path.join(os.path.dirname(PROJECT), "poe2db-assets", "manifest.json"))
    ap.add_argument("--max-px", type=int, default=240)
    args = ap.parse_args()
    source = os.path.dirname(os.path.abspath(args.manifest))
    with open(args.manifest, encoding="utf-8") as fh:
        entries = json.load(fh)["entries"]

    by_type = collections.defaultdict(lambda: collections.defaultdict(list))
    for e in entries:
        by_type[e["type"]][fold(e["name"])].append(e)

    index = {"bases": {}, "uniques": {}, "gems": {}, "runes": {}}
    missing = {"bases": [], "uniques": [], "runes": []}
    for name in sorted(pob_bases()):
        found = by_type["item"].get(fold(name))
        if found:
            index["bases"][fold(name)] = choose(found, item_art=True)
        else:
            missing["bases"].append(name)
    for name in sorted(pob_uniques()):
        found = by_type["unique"].get(fold(name))
        if found:
            index["uniques"][fold(name)] = choose(found, item_art=True)
        else:
            missing["uniques"].append(name)
    for name in sorted(pob_runes()):
        found = by_type["item"].get(fold(name))
        if found:
            index["runes"][fold(name)] = choose(found, item_art=True)
        else:
            missing["runes"].append(name)
    for key, found in by_type["gem"].items():
        index["gems"][key] = choose(found, item_art=False)

    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    files = {}
    size = 0
    for kind, names in index.items():
        for key, image in names.items():
            # images/Art/2DItems/... -> 2ditems/...
            rel = image.split("/", 2)[2].lower()
            if rel not in files:
                dst = os.path.join(OUT, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                src = os.path.join(source, image)
                with Image.open(src) as im:
                    if max(im.size) > args.max_px:
                        scale = args.max_px / max(im.size)
                        im = im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
                        im.save(dst, "WEBP", quality=90)
                    else:
                        shutil.copyfile(src, dst)
                files[rel] = True
                size += os.path.getsize(dst)
            names[key] = rel
    with open(os.path.join(OUT, "index.json"), "w", encoding="utf-8") as fh:
        json.dump(index, fh, separators=(",", ":"), sort_keys=True)

    print(f"bases {len(index['bases'])}, uniques {len(index['uniques'])}, gems {len(index['gems'])}, "
          f"runes {len(index['runes'])}: "
          f"{len(files)} files, {size / 1e6:.1f} MB")
    for kind, names in missing.items():
        if names:
            print(f"no icon for {len(names)} {kind}: {', '.join(names)}", file=sys.stderr)


if __name__ == "__main__":
    main()
