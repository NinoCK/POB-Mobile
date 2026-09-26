"""
Copies the Path of Building (PoE2) Lua program into the APK assets, for the calculation engine.

Reads   : PathOfBuilding-PoE2/src (Lua modules, classes, game data, latest passive tree)
          PathOfBuilding-PoE2/runtime/lua (PoB's pure Lua libraries: dkjson, xml, base64, sha1)
Writes  : app/src/main/assets/pob/
            <paths as in PoB's src folder>   e.g. Modules/Main.lua, Data/Skills/act_int.lua
            lua/<library>.lua                 PoB's runtime/lua modules
            manifest.xml                      PoB version information
            version.txt                       PoB commit, used to invalidate caches on the device

Only Lua and data files are copied; PoB's images, export tools and updater are left out.
On Windows the mod parser cache is then completed with tools/pob_reference.py modcache (PoB's own
runtime), which saves PoB from parsing a few hundred mod lines at every start.
Usage   : python tools/build_pob_assets.py [--pob ../PathOfBuilding-PoE2] [--no-modcache]
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
OUT = os.path.join(PROJECT, "app", "src", "main", "assets", "pob")
NEWLINE = chr(10)

# Top-level files of PoB's src folder needed to run it headless
SRC_FILES = ["Launch.lua", "GameVersions.lua", "_SimpleGraphic.def.lua", "HeadlessWrapper.lua"]
# Folders copied recursively (Lua files only)
SRC_DIRS = ["Modules", "Classes", "Data"]
# PoB's own Lua libraries from runtime/lua that the program requires
RUNTIME_LIBS = ["dkjson.lua", "xml.lua", "base64.lua", "sha1", "sha2.lua"]


def latest_tree_version(src):
    text = open(os.path.join(src, "GameVersions.lua"), encoding="utf-8").read()
    versions = re.search(r"treeVersionList\s*=\s*\{([^}]*)\}", text).group(1)
    return re.findall(r'"([^"]+)"', versions)[-1]


def copy_lua_tree(src_dir, dst_dir):
    count = 0
    for root, _dirs, files in os.walk(src_dir):
        for name in files:
            if not name.endswith(".lua"):
                continue
            rel = os.path.relpath(os.path.join(root, name), src_dir)
            dst = os.path.join(dst_dir, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(os.path.join(root, name), dst)
            count += 1
    return count


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pob", default=os.path.join(PROJECT, "..", "PathOfBuilding-PoE2"))
    ap.add_argument("--no-modcache", action="store_true", help="keep PoB's bundled mod cache as it is")
    args = ap.parse_args()
    pob = os.path.abspath(args.pob)
    src = os.path.join(pob, "src")

    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)

    count = 0
    for name in SRC_FILES:
        shutil.copyfile(os.path.join(src, name), os.path.join(OUT, name))
        count += 1
    for d in SRC_DIRS:
        count += copy_lua_tree(os.path.join(src, d), os.path.join(OUT, d))

    tree_version = latest_tree_version(src)
    tree_dir = os.path.join(OUT, "TreeData", tree_version)
    os.makedirs(tree_dir)
    shutil.copyfile(os.path.join(src, "TreeData", tree_version, "tree.lua"), os.path.join(tree_dir, "tree.lua"))
    count += 1

    lib_out = os.path.join(OUT, "lua")
    os.makedirs(lib_out)
    for name in RUNTIME_LIBS:
        path = os.path.join(pob, "runtime", "lua", name)
        if os.path.isdir(path):
            count += copy_lua_tree(path, os.path.join(lib_out, name))
        else:
            shutil.copyfile(path, os.path.join(lib_out, name))
            count += 1

    # PoB treats a manifest without branch / platform as a development checkout (dev mode: auto-saves,
    # extra checks); mark the copy as an installed release
    manifest = open(os.path.join(pob, "manifest.xml"), encoding="utf-8").read()
    manifest = re.sub(r'<Version number="([^"]*)"\s*/>', r'<Version number="\1" branch="master" platform="android" />', manifest, count=1)
    with open(os.path.join(OUT, "manifest.xml"), "w", encoding="utf-8", newline=NEWLINE) as f:
        f.write(manifest)
    try:
        commit = subprocess.check_output(["git", "-C", pob, "rev-parse", "HEAD"], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        commit = "unknown"
    with open(os.path.join(OUT, "version.txt"), "w") as f:
        f.write(f"{commit}\n{tree_version}\n")

    if not args.no_modcache and sys.platform == "win32" and os.path.exists(os.path.join(pob, "runtime", "lua51.dll")):
        subprocess.check_call([sys.executable, os.path.join(HERE, "pob_reference.py"), "modcache", "--pob", pob])

    size = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(OUT) for f in fs)
    print(f"{count} files, {size / 1e6:.1f} MB, tree {tree_version}, PoB {commit[:10]} -> {OUT}")


if __name__ == "__main__":
    main()
