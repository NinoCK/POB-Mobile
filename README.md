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
- Jewels on the tree, as in PoB: radius rings around sockets holding radius jewels (the conquering
  jewels' own circles), the socketed jewel's art, passives conquered by Timeless jewels, passives
  allocated by items. Selecting a socket shows the jewel radii in PoB's colours and tints the
  passives inside them. The node panel shows PoB's text for passives changed by jewels (Time-Lost
  jewels' added stats, conquered passives) and a socketed jewel's tooltip.
- Jewels that change the pathing rules work like in PoB: passives in the ring of Controlled
  Metamorphosis, near From Nothing's keystone or near keystones with Entwined Realities can be
  allocated without a path, and Split Personality adds another class's start.
- Heat map (PoB's node power): offence / defence, or any of PoB's statistics (Life, a DPS type,
  resistances...), up to a chosen distance, with PoB's power report (the best passives for the
  statistic, per point along their path; tap one to show it).

### Calculations (Path of Building's engine)

The app runs Path of Building's Lua program inside the app (LuaJIT, the same version PoB ships),
so the numbers are PoB's. The screens follow PoB's tabs:

- **Tree**: key numbers (DPS, Life, ES, EHP) above the tree, updated after every change; the node
  panel shows PoB's stat changes for allocating or removing the node along the planned path
  ("Allocating this node will give you: …"), including weapon-set allocation.
- **Stats**: PoB's sidebar: main skill selection (skill group, active skill, stat set, part, stages,
  mines, minions, weapon set I / II), all displayed stats with PoB's colours, warnings. Tap a stat
  for its breakdown.
- **Skills**: socket groups and gems with PoB's active / inactive reasons; enable, Full DPS, main
  skill, weapon sets, labels; gem level / quality / enabled; add gems (search), remove gems, paste
  groups in PoB's text format, skill sets. Gems show the game's icons.
- **Items**: the game's item art (uniques' own art, the base's for other items) in the slots,
  lists and tooltips; item and weapon sets, every slot with PoB's item tooltips (including "Equipping /
  Removing this item will give you"), flask / charm activation, changing and unequipping items,
  adding items pasted from the game or from PoB, deleting items. Crafting, on PoB's item editor:
  - a new item (type, base, rarity), one of PoB's uniques or rare templates (variants and rolls), or
    an item of the build; the rarity (normal / magic / rare), name and item level can be changed;
  - prefixes and suffixes up to the item's limits (including "+1 Prefix Modifier allowed" and the
    like), each chosen from every source PoB has data for: the base's regular modifiers, essences
    (the tiers are the essences, Lesser to Perfect), desecrated modifiers, rune-influenced
    modifiers, the item class's other modifiers, or any modifier of the item's table. The picker
    searches modifiers, tiers and tags, shows each tier's values and item level (greyed above the
    item's level), PoB's stat changes for a tier, and sorts by any of PoB's statistics (the change
    of Full DPS, Life...);
  - on each prefix / suffix: the tier, a roll for each value with its exact number, best / worst
    rolls, fractured;
  - an item pasted from the game or imported (plain lines) can be turned into prefixes and
    suffixes: its lines are matched to modifiers, tiers and rolls, and lines that match none stay
    as custom lines;
  - custom text lines and PoB's "Add modifier" lists, the rolls of implicits and unique modifiers,
    runes and soul cores, quality and catalysts, anoints and corruption (PoB's popups).
- **Calcs**: the Calcs tab's own skill selection and calculation mode, all sections, and PoB's
  breakdowns of each value (formulas and modifier tables with their sources; a passive listed as a
  source can be shown on the tree).
- **Config**: the configuration options PoB shows for the build (or all of them), configuration
  sets, and custom modifiers.

Build codes import the whole build (tree, items, skills, configuration) and export the whole build.
A build's PoB data is saved next to it on the device. The tree is edited in the app, the rest in
the engine. Undo / redo works on the current screen, like Ctrl+Z / Ctrl+Y in PoB's tabs: the tree's
history on the Tree screen, PoB's own history of the Skills, Items, Calcs and Config tabs on
theirs (100 changes each).

PoE2 has no cluster jewels (the tree has no expansion sockets and PoB-PoE2 has none), so there is
nothing to show for them.

### Character import

⋮ > "Import character…" imports a character of your Path of Exile account, like the Character
Import section of PoB's Import/Export tab:

- Sign in on pathofexile.com in the browser (OAuth, as PoB does it: PoB's public client `pob`, with
  the code sent back to a server of the app on `http://localhost:49082`–`49084`; the app only asks
  for the `account:characters` scope). "Return to the app" on the page shown afterwards opens the
  app again. The tokens are kept in the app's no-backup storage and renewed with the refresh token;
  "Sign out" deletes them.
- Choose the league and the character (PoE2 characters, from `api.pathofexile.com/character/poe2`).
- Import into a new build named after the character, or into the open build; the passive tree and
  jewels and / or the items and skills, with PoB's options (delete jewels, skills, equipment; ignore
  weapon swap).

The import itself is PoB's (`ImportTab:ImportPassiveTreeAndJewels` / `ImportItemsAndSkills`,
called by `engine/api/Import.lua`): class and ascendancy, passives, weapon-set passives,
attribute choices, quest rewards in the configuration, character level, jewels, equipment, runes,
flasks and charms, skill gems and supports. The app then takes the tree and level from the build.
An import into the open build can be undone (the tree on the Tree screen, the items and skills on
theirs).

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
| `app/src/main/assets/engine/Api.lua`, `api/*.lua` | The app's API over PoB's objects, returning JSON (`Craft.lua` drives PoB's item editor controls and popups, `CraftMods.lua` is the crafting model: modifier pools by source, tiers, rolls, conversion of pasted items; `Power.lua` runs PoB's node power builder in steps; `Import.lua` runs PoB's character import) |
| `engine/*.kt` | JNI bindings, the engine thread, the open build's session and data models |
| `io/PoeAccount.kt` | pathofexile.com sign-in (OAuth with PKCE, local redirect server) and the character endpoints of the Path of Exile API |

LuaJIT runs as an interpreter (`jit.off()` in `Host.lua`): PoB's code is branchy and short-running,
and trace compilation made opening a build several times slower on the emulator.

PoB's files are used as they are. The engine changes a few things after PoB starts, none of them
affecting the calculations:

- `copyTableSafe` (`Host.lua`): its deep copies of attribute nodes (`PassiveSpec:SwitchAttributeNode`)
  wrote into the shared tree data and made memory grow with every tree change in the app.
- `DrawStringCursorIndex` (`Host.lua`): the app lays out text itself, so text always "fits" (the
  headless stub made PoB shorten some labels to "...").
- The Skills tab's undo states (`Api.lua`) leave out the skill data rebuilt at every calculation
  (0.6 MB per state otherwise), and a new build starts its tabs' undo history like a loaded one.
- Crafting (`CraftMods.lua`): the item classes' modifier tables also find desecrated modifiers by
  id (so a crafted prefix / suffix can be one; PoB's own lists are unchanged), and `Item:Craft`
  also takes a roll for each value of a modifier and marks desecrated modifiers. It only runs in
  the item editor; items are calculated from their lines as before.

The app's pathing is a port of PoB's (`tree/PassiveSpec.kt`), including the jewels that change it;
the engine describes the build's jewels (`api.treeOverlay`), and the unit tests check that PoB
accepts every tree the app allocates with them.

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

## Updating the item and gem icons

The icons in `app/src/main/assets/icons` come from [poe2db](https://poe2db.tw/us/), downloaded by
`../poe2db-assets/fetch_poe2db_assets.py` (see its README). With that download next to this folder:

```bash
python tools/build_icons.py
```

The script picks the icon of every item base and unique of the bundled Path of Building
(`assets/pob/Data`) and of every gem and granted skill, scales item art down to 240 px, and writes
`index.json` (folded names to files) for `ui/GameIcons.kt`. Run it again after updating Path of
Building so new bases and uniques get their icons.

## Code layout

| Path | Contents |
| --- | --- |
| `tree/PassiveTree.kt` | Tree loading, node positions and connector geometry (port of PoB `PassiveTree.lua`) |
| `tree/PassiveSpec.kt` | Allocation state, pathing, dependencies, weapon sets (port of PoB `PassiveSpec.lua`) |
| `tree/TreeJewels.kt` | The build's jewels from the engine, and PoB's jewel radius membership |
| `tree/TreeText.kt` | Search and the stats summary |
| `engine/` | Path of Building's engine: native bindings, session, data models |
| `io/PobCode.kt` | PoB build code import / export |
| `io/BuildStore.kt` | Saved builds, point settings and each build's PoB data |
| `ui/TreeRenderer.kt` | Tree drawing (PoB `PassiveTreeView.lua` draw order and colours) |
| `ui/QuadBatch.kt`, `ui/SpriteCache.kt` | Batched sprite drawing, atlases and image loading |
| `ui/GameIcons.kt` | Item and gem icons by name |
| `ui/StatsScreen.kt`, `CalcsScreen.kt`, `SkillsScreen.kt`, `ItemsScreen.kt`, `ConfigScreen.kt` | PoB's tabs |
| `ui/CraftEditor.kt` | PoB's item editor and its popups, drawn from the controls the engine reports |
| `ui/HeatMap.kt` | Heat map settings and PoB's power report |
| `TreeViewModel.kt` | App state, gestures, point caps, undo / redo |

Not included: PoB's party and notes tabs, the trade site integration, and comparing builds.

## Licence notes

Path of Building is MIT licensed, and LuaJIT is MIT licensed (`native/luajit/COPYRIGHT`). The passive
tree, item and gem art belongs to Grinding Gear Games (the item and gem icons as published by poe2db,
CC BY-NC-SA 3.0); this app is intended for personal use and is not affiliated with Grinding Gear
Games.
