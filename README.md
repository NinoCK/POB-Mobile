# PoE2 Passive Tree (Android)

A Kotlin / Jetpack Compose Android app that shows the Path of Exile 2 passive tree, lets you plan
passive point allocation, and calculates damage and all other stats with Path of Building's own
calculation engine. Tree data, pathing rules, art and calculations come from
[Path of Building Community (PoE2)](https://github.com/PathOfBuildingCommunity/PathOfBuilding-PoE2).

## Features

### Passive tree

- Full tree (version 0.5) with the game art: node icons and frames, orbit arcs, mastery art,
  class and ascendancy backgrounds.
- Zoom with the + / − buttons, pinch, the mouse wheel (emulator, Chromebooks, mouse on tablets) or a
  double tap on empty space; when zoomed out far, a tap zooms into that area. Drag to pan.
  "Show whole tree" and "Go to class start" buttons reset the view.
- Portrait: controls at the top, node details in a panel at the bottom. Landscape: controls and
  node details in a panel on the left, the tree on the right.
- Tap a node to see its details, tap again (or use the button) to allocate / remove it.
  Allocation follows PoB: the shortest path is allocated, removing a node removes everything that
  depended on it.
- To take a different route, tap the nodes along it one after another, moving away from the tree,
  then tap the last one again to allocate. The highlighted preview shows the route that will be
  allocated; "Use shortest path" in the node panel goes back to PoB's route.
- Class and ascendancy selection (including switching by tapping another ascendancy's node, and
  "connect path" when changing to a class whose start is not connected).
- Weapon set 1 / 2 allocation modes with PoB's rules (keystones and jewel sockets only from the
  main tree, weapon-set branches promoted back to the main tree when pathed through).
- Attribute nodes: choose Strength / Dexterity / Intelligence.
- Point limits enforced (hard cap): level-based main pool, weapon-set points, ascendancy points,
  editable per build.
- Search by node name or stat text, with highlights and next / previous navigation.
- Multiple builds saved on the device; undo / redo of tree changes.

### Calculations (Path of Building's engine)

The app runs Path of Building's Lua program, unmodified, inside the app (LuaJIT, the same version
PoB ships), so the numbers are PoB's. The screens follow PoB's tabs:

- **Tree**: key numbers (DPS, Life, ES, EHP) above the tree, updated after every change; the node
  panel shows PoB's stat changes for allocating or removing the node along the planned path
  ("Allocating this node will give you: …"), including weapon-set allocation.
- **Stats**: PoB's sidebar: main skill selection (skill group, active skill, stat set, part, stages,
  mines, minions, weapon set I / II), all displayed stats with PoB's colours, warnings. Tap a stat
  for its breakdown.
- **Skills**: socket groups and gems with PoB's active / inactive reasons; enable, Full DPS, main
  skill, weapon sets, labels; gem level / quality / enabled; add gems (search), remove gems, paste
  groups in PoB's text format, skill sets.
- **Items**: item and weapon sets, every slot with PoB's item tooltips (including "Equipping /
  Removing this item will give you"), flask / charm activation, changing and unequipping items,
  adding items pasted from the game or from PoB, deleting items.
- **Calcs**: the Calcs tab's own skill selection and calculation mode, all sections, and PoB's
  breakdowns of each value (formulas and modifier tables with their sources; a passive listed as a
  source can be shown on the tree).
- **Config**: the configuration options PoB shows for the build (or all of them), configuration
  sets, and custom modifiers.

Build codes import the whole build (tree, items, skills, configuration) and export the whole build.
A build's PoB data is saved next to it on the device. The tree is edited in the app, the rest in
the engine; undo / redo covers tree changes.

## Building

Requirements: JDK 17+, Android SDK with platform 37 and NDK 28.2.13676358 (Android Studio installs
them).

```bash
./gradlew assembleRelease
```

The APK (arm64-v8a, armeabi-v7a, x86_64) is written to
`app/build/outputs/apk/release/app-release.apk`. It is signed with the local debug key so it can be
sideloaded directly.

Unit tests (the real tree data and the real engine; Windows, see below):

```bash
./gradlew testDebugUnitTest
```

## The calculation engine

| Path | Contents |
| --- | --- |
| `native/luajit` | LuaJIT at the commit Path of Building ships (2.1.1784580905, with its syntax extensions) |
| `native/gen/<abi>` | Target-specific LuaJIT sources (interpreter, generated headers) from `tools/build_luajit.py` |
| `native/pob_jni.c` | JNI bridge: runs Lua chunks, and gives Lua the host services (files, zlib, time, log) |
| `native/host/win-x64` | Desktop build of the bridge for the unit tests, and `luajit.exe` |
| `app/src/main/assets/pob` | Path of Building's Lua program and data (from `tools/build_pob_assets.py`) |
| `app/src/main/assets/engine/Host.lua` | Runs PoB headless (like PoB's `HeadlessWrapper.lua`) |
| `app/src/main/assets/engine/Api.lua`, `api/*.lua` | The app's API over PoB's objects, returning JSON |
| `engine/*.kt` | JNI bindings, the engine thread, the open build's session and data models |

LuaJIT runs as an interpreter (`jit.off()` in `Host.lua`): PoB's code is branchy and short-running,
and trace compilation made opening a build several times slower on the emulator.

### Updating Path of Building

With a newer PoB checkout next to this folder (`../PathOfBuilding-PoE2`):

```bash
python tools/build_pob_assets.py
```

This copies PoB's Lua files (no images) into `assets/pob`, and on Windows completes PoB's mod
parser cache with the lines PoB would otherwise parse at every start (`tools/pob_reference.py
modcache`; the entries PoB ships are kept as they are).

If PoB's `runtime/lua51.dll` reports a newer LuaJIT, update `native/luajit` to that commit
(PoB's SimpleGraphic repository pins it in `vcpkg-ports/ports/luajit`) and regenerate the target
sources with Visual Studio Build Tools and the NDK installed:

```bash
python tools/build_luajit.py --host
```

### Checking the results against Path of Building

`tools/pob_reference.py` runs the desktop Path of Building headless with its own LuaJIT DLL:

```bash
python tools/pob_reference.py generate    # test characters from tools/fixtures/recipes.lua
python tools/pob_reference.py reference   # PoB's results for each of them
```

The fixtures and results are in `app/src/test/resources/builds`. The unit tests check that the
app's engine gives exactly the same results (every output value and the whole sidebar), that the
app's tree pushed into PoB matches the tree from the build's XML, and that the Calcs, Config, Skills
and Items functions work on every fixture.

## Updating the tree data

The bundled assets in `app/src/main/assets/tree` are generated from a Path of Building checkout
(expected next to this folder as `../PathOfBuilding-PoE2`). Requires Python 3.14+ (for the
standard-library zstd module) and Pillow.

```bash
python tools/build_assets.py --version 0_5
```

The script copies `tree.json`, decodes the compressed DDS texture arrays into WebP images, crops
the transparent padding newer texture arrays have, and packs node art into small atlases used for
fast drawing when zoomed out. Curved connections are drawn by bending the straight connector art
along the orbit, so PoB's orbit arc images are not bundled.

## Code layout

| Path | Contents |
| --- | --- |
| `tree/PassiveTree.kt` | Tree loading, node positions and connector geometry (port of PoB `PassiveTree.lua`) |
| `tree/PassiveSpec.kt` | Allocation state, pathing, dependencies, weapon sets (port of PoB `PassiveSpec.lua`) |
| `tree/TreeText.kt` | Search and the stats summary |
| `engine/` | Path of Building's engine: native bindings, session, data models |
| `io/PobCode.kt` | PoB build code import / export |
| `io/BuildStore.kt` | Saved builds, point settings and each build's PoB data |
| `ui/TreeRenderer.kt` | Tree drawing (PoB `PassiveTreeView.lua` draw order and colours) |
| `ui/QuadBatch.kt`, `ui/SpriteCache.kt` | Batched sprite drawing, atlases and image loading |
| `ui/StatsScreen.kt`, `CalcsScreen.kt`, `SkillsScreen.kt`, `ItemsScreen.kt`, `ConfigScreen.kt` | PoB's tabs |
| `TreeViewModel.kt` | App state, gestures, point caps, undo / redo |

Not included: jewels' radius effects drawn on the tree, cluster jewels, the node power heat map,
item crafting (items are added as text), and PoB's per-tab undo for items, skills and
configuration.

## Licence notes

Path of Building is MIT licensed, and LuaJIT is MIT licensed (`native/luajit/COPYRIGHT`). The passive
tree art belongs to Grinding Gear Games; this app is intended for personal use and is not affiliated
with Grinding Gear Games.
