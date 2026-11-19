# Sea of Stars: Thor Edition — plan

Written 2026-11-16. The user's verdict on v1 (tabbed Android-drawn companion): "pretty mediocre".
The new target is the concept image in `docs/mockup/concept.png` and the interactive mockup
(https://claude.ai/artifact/8NYyZDFQDKyM32qPQy7hiA, source `docs/mockup/thor-edition.html`):
the game should feel as if its developers shipped a dual-screen Thor version.

**RESUME HERE:** read NOTES.md "Thor Edition status, 2026-11-16 evening" first; Phases 0-1 are largely done on device.
revised architecture (engine layer = live area view from a second world camera; app layer = dashboard drawn with
game assets). The debug channel (`duo_cmd.txt`, see NOTES.md) drives experiments without rebuilding.

## Phase 0 results (device, 2026-11-16)
- `displayChanged(1, surface)` returns true; `Display.displays.Length` becomes 2; a Camera with `targetDisplay=1`
  renders live on the bottom screen (Vulkan). Top screen keeps rendering normally. `Display.Activate` is not
  needed (and its icall is stripped; `rendering` size stays 0x0, harmless).
- Geometry quirk: Unity treats display 1 as 1920x1080 and only fills the centred 1240 columns (x 340..1580),
  whatever size the Surface has. Fix in place: SurfaceView laid out 1920x1080 with `translationX = -340`.
  A camera on display 1 therefore has pixel size 1920x1080 and we see its middle 1240x1080.
- `Camera.rect` does not confine the clear on display 1 (whole target is cleared). Don't rely on viewport rects.
- A second WORLD camera works: copying the main camera's pose/projection shows the game world on the bottom
  screen (shots/p1_z1.png). Zoomed out 2.5x it shows the whole small level incl. the black occluder shapes and
  backdrop plane at the level edge (shots/p1_z25.png). This is the basis for the area map.
- FAILED: creating our own Canvas (ScreenSpaceCamera on the probe camera) and re-parenting the four InGameHud
  panels onto it crashed the game with SIGSEGV in UnityMain ~0.2 s later (`probe:hud2`). Do not repeat as is.
  Untried gentler variants: Instantiate `UIManager.canvasPrefab`; Overlay-mode canvas with `targetDisplay=1`;
  moving a whole View instead of HUD panels; adding UniversalAdditionalCameraData to the camera first.
- The Thor sleeps after a short idle and Unity pauses (commands queue up). `svc power stayon usb` is set now.

## What "native" means here

1. **Top screen is only the game.** No HUD on it: party status, battle UI and zone name move down.
2. **Bottom screen is drawn in the game's own look**: its font, its frame art, its portraits and
   icons, loaded from the install at runtime (never shipped in our APK or repo).
3. **The game's own menus open on the bottom screen**, drawn by the game engine, so equip / items /
   relics behave exactly as the game intends and we write nothing into game state ourselves.
4. **Things the game never had are added in its style**: a charted dungeon map, a lock-break board.

## Research findings (from dump/dump.cs and extract/unity_dexdump.txt)

| Finding | Where | Why it matters |
|---|---|---|
| `UnityPlayer.displayChanged(int, Surface)` is public in this build | unity_dexdump.txt:2309364 | Unity's own hook for a secondary display. We can hand the engine a Surface on the bottom screen. Unity documents multi-display for Android (GLES and Vulkan). |
| `UnityEngine.Display.displays`, `Camera.targetDisplay`, `Canvas.targetDisplay` all survive stripping | dump.cs:743139, 635701, 1019172 | A camera or canvas can be pointed at display 1 from native code. `Display.Activate()` is stripped from managed code; if needed call the icall `UnityEngine.Display::ActivateDisplayImpl` via `il2cpp_resolve_icall`. |
| All game UI lives on ONE canvas owned by `UIManager` (`canvasInstance` 0x110, `uiCameraInstance` 0x100, `layers` 0x128, `canvasPrefab` 0x28, `UICameraPrefab` 0x30). URP; UI camera is a stacked overlay. `CanvasUpscaleViewport` does the pixel-art upscale. | dump.cs:142974, 1037315 | To put a menu on the bottom we instantiate a second canvas + camera from the same prefabs with `targetDisplay = 1` and re-parent the View there. |
| Views: `GameMenu` (sections Home / Character / Inventory / Relic, `ChangeSection`), `GameMenuTouch`, `MapScreen` + `MapScreenMobile`, `PauseMenu`, `InGameHud` | dump.cs:154721, 160136, 162924, 158202 | This is the mobile build, so touch-operable variants of every menu already exist. |
| `InGameHud` has `HideHPInstant`, `HideBattleUIInstant`, `HideZoneNameInstant`, `HideRightBarPanelInstant`; panels `playerStatuses`, `playerBattleUI`, `rightBarPanel`, `zoneNamePanel` | dump.cs:158202 | Clean top screen without touching game state: disable the panel GameObjects or their CanvasGroups. |
| `MapScreen.currentObjectives` (List<MapObjective>: `objectiveLevel` + optional position); `ActivityData.SubActivity.activityObjectives`; `LevelDefinition.levelNameLocId` | dump.cs:159925, 7374, 136437 | The game stores the objective as a destination level, not as a sentence. Bottom screen shows "Head to <level name>". There is no quest log in the game; do not invent one. |
| `MapRegionProgressInfo`, `MapLocationRegionManager`, `MapParrotHint` | dump.cs:160074, 138018, 160020 | Treasure / conch / fish counts per region. The game gates these behind the parrot relic; keep the same gate. |
| No dungeon map exists (`CryptMapScreen` is only a room list). Followers path-find on a Unity NavMesh (`AbstractPathFinderAgent`, `NavMeshPath`, `samplePositionOnNavMesh`). `NavMesh.CalculateTriangulation` is stripped. `Physics.Raycast` is present. | dump.cs:185618, 989763 | The dungeon map has to be charted by us: sample walkable floor near the party with `NavMesh.SamplePosition` (check it survived) or downward `Physics.Raycast`. |
| Level objects: `LevelManager.savePointRefs`, `.currentLevelEntrances`, `.currentLevelCamera`; `Savepoint`, `CampingSpot`, `Chest : LootTrigger` (+`TreasureId`), `LevelEntrance`, `Lever`, `CameraBounds.colliders` | dump.cs:136864, 91521, 12558, 55951, 93997, 88616, 11310 | Real marker positions and level extents for the map. |
| Battle data already read by reader.cpp: locks, turnsToAction, HP, Live Mana, combo points; game widgets `SpellLockUIItem`, `ComboPointsPanel`, `PlayerBattleUI` hold the sprites | NOTES.md; dump.cs:145461, 158049, 158779 | Battle board is mostly presentation work; icons can be read back from those widgets' Sprites the same way portraits are. |

## Architecture

```
Top display 0      UnityPlayer (as today)               game world, HUD panels disabled
Bottom display 4   Presentation
                     ├─ SurfaceView  → UnityPlayer.displayChanged(1, surface)   engine layer (game menus, map screen)
                     └─ DuoView (Android Canvas, transparent where engine shows)  our layer (dashboard, dungeon map, battle board)
libsosduo.so       reader thread (snapshots, 5 Hz) + main-thread tick (hook on InputManager.Update)
```

Two layers on the bottom screen, on purpose. The engine layer gives pixel-identical game menus for
free. Our layer covers what the game has no screen for. If Phase 0 fails, the engine layer is dropped
and menus are app-drawn from reader data in the same visual language (fallback, see Phase 4b).

Rules carried over: resolve everything by name through il2cpp exports (offsets above are hints for
this build only); call game methods only on the main-thread tick; no writes to save or game state
by us. UI-only changes (hiding panels, moving Views, opening the game's own menu) are in bounds.

## Phases

### Phase 0 — prove the second display (decides the menu approach)
- [x] Code: `Presentation` gains a `SurfaceView` under the companion view; on `surfaceCreated/Changed`
      call `UnityPlayer.displayChanged(1, surface)` by reflection, `displayChanged(1, null)` on destroy.
- [x] Code: main-thread probe `probeDisplay()` logs `Display.displays.Length`, each display's
      rendering size, and once a level is loaded creates a GameObject with a `Camera`
      (`targetDisplay=1`, solid magenta clear, cullingMask 0). Toggled by `/sdcard/Android/data/com.evtrai.sosduo/files/duo_probe.txt`.
- [ ] Device: magenta on the bottom screen = engine can draw there. Screenshot `-d 4630946482288158084`.
- [ ] Device: frame-time check on the top screen with display 1 active (target: no drop from 60).
- [ ] If displays.Length stays 1: try `il2cpp_resolve_icall("UnityEngine.Display::ActivateDisplayImpl")`,
      then give up on the engine layer and take Phase 4b.

### Phase 1 — clean top, new bottom shell
- [ ] Hide top HUD: `InGameHud` panel GameObjects `SetActive(false)` while Duo is running; setting in
      our quick bar to bring them back. Verify nothing in battle flow waits on `PlayerBattleUI.fullyOpened`
      (if it does, zero the CanvasGroup alpha instead of deactivating).
- [ ] Asset loader on the main thread: game font (TMP font asset atlas + glyph table → Android bitmap
      font), 9-slice frame sprites from `UISkin`, damage-type icons from `SpellLockUIItem`, portraits
      (fix washed-out colour: UNorm format). Cache to app-private storage keyed by game version.
- [ ] `DuoView` replaces `CompanionView`: header (area, gold, time), party column, objective, map
      well, quick bar. Integer-scaled pixel rendering, nearest-neighbour everywhere.
- [ ] State machine from reader: title / loading / cutscene (dim logo) → explore → battle → menu.

### Phase 2 — battle board
- [ ] Enemy cast cards: countdown octagon, locks with broken state, HP if the game reveals it.
- [ ] "Who breaks what": match remaining locks against each character's available damage types
      (basic attack + known skills). Needs skill damage-type data (`CombatMoveSpellLockSettings`).
- [ ] Live Mana on field and per-character boost level; combo points with affordable combos.
- [ ] Verify on device against a real fight with casting enemies (never verified in v1).

### Phase 3 — dungeon cartographer
- [ ] Probe which sampler works: `NavMesh.SamplePosition` over a grid inside `CameraBounds`, else
      `Physics.Raycast` straight down on the walkable layer. Budget: ≤ 0.5 ms per frame on the main tick.
- [ ] Chart: 0.5-unit cells, mark walkable + height; reveal cells within a radius of the party's path.
      Persist per level id in app storage (`maps/<levelId>.bin`).
- [ ] Render: parchment style (frame + paper from game sprites if a suitable one exists, else our
      own), rooms inked from the walkable mask with contour tracing, height shading, fog at the frontier.
- [ ] Markers from real objects once seen within reveal radius: save point, camp spot, chest
      (open / closed from its GPI state), level entrances with destination names.
- [ ] Objective strip: `MapObjective.objectiveLevel` → level name; arrow toward the matching entrance
      when that entrance is charted.
- [ ] World-map levels: show the game's own `MapScreen` on the engine layer instead of a chart.

### Phase 4 — menus below (engine layer)
- [ ] Second canvas + UI camera on display 1 from `UIManager` prefabs; 16:9 viewport letterboxed
      inside 1240×1080 (1240×697), our quick bar in the remaining strip.
- [ ] Hook `UIManager` screen instantiation: when `GameMenu` / `MapScreen` opens, re-parent it to the
      bottom canvas; restore on close. Pause handling: check `PauseManager` so the world can keep
      rendering on top while the menu is open below.
- [ ] Quick bar: Party / Items / Relics / World Map open the game's menu at that section
      (`GameMenu.ChangeSection`) exactly as the game's own button would.
- [ ] Touch: forward bottom-screen touches into `UnityPlayer.injectEvent` scaled to the bottom canvas.
      GraphicRaycaster does not know which display a touch came from on Android, so block raycasts
      on the top canvas while a bottom touch is down.
- **4b fallback (only if Phase 0 fails):** app-drawn Party / Items / Relics pages from reader data,
      view-only, with the quick bar opening the game's real menu on the top screen.

### Phase 5 — polish and release
- [ ] Transitions between states (slide / fade matched to the game's menu timing).
- [ ] Cutscene dimming, sleep/wake, controller stays on the game (`FLAG_NOT_FOCUSABLE` as today).
- [ ] Release build (no CheckJNI), version 1.0.0, README + screenshots, full play session.

## Open questions for the user
- Hiding the top HUD in battle removes the on-character action menu? No: the action selector is a
  separate world-anchored widget and stays on top. Only status panels move.
- Earlier guidance says companions stay view-only. This plan keeps to that: we never write state;
  the only "actions" are opening the game's own menus and operating them as the game allows.
