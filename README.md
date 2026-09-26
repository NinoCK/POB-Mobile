# PoE2 Passive Tree (Android)

A Kotlin / Jetpack Compose Android app that shows the Path of Exile 2 passive tree and lets you
plan passive point allocation. Tree data, pathing rules and art come from
[Path of Building Community (PoE2)](https://github.com/PathOfBuildingCommunity/PathOfBuilding-PoE2).
No calculations are performed - only the tree itself.

## Features

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
- Allocated stats summary (text totals only).
- Multiple builds saved on the device; undo / redo.
- Import and export Path of Building 2 build codes (passive tree only).

## Building

Requirements: JDK 17+, Android SDK with platform 37 (Android Studio installs it).

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. It is signed with the
local debug key so it can be sideloaded directly.

Unit tests (run against the real tree data):

```bash
./gradlew testDebugUnitTest
```

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
| `io/PobCode.kt` | PoB build code import / export |
| `io/BuildStore.kt` | Saved builds and point settings |
| `ui/TreeRenderer.kt` | Tree drawing (PoB `PassiveTreeView.lua` draw order and colours) |
| `ui/QuadBatch.kt`, `ui/SpriteCache.kt` | Batched sprite drawing, atlases and image loading |
| `TreeViewModel.kt` | App state, gestures, point caps, undo / redo |

Not included: jewels and their radius effects, cluster jewels, masteries, and any stat calculations.

## Licence notes

Path of Building is MIT licensed. The passive tree art belongs to Grinding Gear Games; this app is
intended for personal use and is not affiliated with Grinding Gear Games.
