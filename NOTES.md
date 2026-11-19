# Sea of Stars Duo: research notes

Target: com.playdigious.seaofstars 3.0.60158 (Play), arm64, Unity IL2CPP. Metadata v31 is not encrypted.
The dump is in dump/ (Il2CppDumper 6.7.46): dump.cs, script.json, il2cpp.h, DummyDll.
The offsets below are for this build only. At runtime, resolve fields by name through the il2cpp_* exports.

## Protection
- No PairIP, no LVL licence check. split_gpdeku is Google's Play Games overlay (screen capture).
- The launcher is com.unity3d.player.UnityPlayerActivity. The app also includes Play Games Services, Firebase (Analytics, Crashlytics) and Helpshift.
- Saves: /sdcard/Android/data/com.playdigious.seaofstars/files/SOS_SaveSlot*.sos, encrypted by CryptSaveManager. Copy them as they are.

## Entry points
- The singleton pattern is `Manager<T>` with a `private static T instance`. It covers CombatManager, PlayerPartyManager, InventoryManager, LevelManager and SaveManager.
- `CombatManager.currentEncounter` (Encounter, 0x150) is the active battle. `maxLiveMana` is at 0x148.
- `Encounter`: enemySlots 0xB8, playerSlots 0xC0, liveManaHandler 0x88, bossEncounter 0x59, xpGained 0x158.
- `CombatActor`: isPlayer 0xC0, slot 0xC8, dead 0xD8, targets 0x78.
- `EnemyCombatActor`: enemyData 0x110, castingData 0x128 (EnemyCastingData), hideHP 0x108.
- `EnemyCastingData`: spellLocks List<EnemyCastingLockData> 0x18, actionTimerLocks 0x20, **turnsToAction 0x24**, totalSpellLocks 0x28.
- `EnemyCastingLockData` (struct): damageType 0x0, revealed 0x8.
- `EnemyCombatTarget.currentHP` is at 0x98. Player HP is in `PlayableCharacterData.currentHP` (0x78) and PlayableCharacterSaveData.
- `PlayerCombatActor`: characterDefinitionId 0x100, liveManaHandler 0x150 (PlayerActorLiveManaHandler).
- Combo points: `maxComboPoints` at dump.cs:133012. The owning class still needs to be identified.
- Overworld: `WorldMapPlayerController`, `PlayerController` (both PlayerPartyCharacter).

- Battle links: `Encounter.enemySlots[i]` (EncounterEnemySlot) has `.enemy` (EnemyCombatActor, 0x48). Any EncounterSlot has `.currentActor` (0x30). `CombatActor.targets[i]` (CombatTarget) has `.owner` (0x88) and `.dead` (0x90). For enemies, `EnemyCombatTarget.currentHP` is at 0x98.

## HANDOFF / STATUS (read this first)
Updated 2026-11-08 by Claude, working with the user. The user asked for these notes so a later session can resume.

### Goal
Build a dual-screen host app for **Sea of Stars (Android, Play)** on the user's **AYN Thor**:
- The game runs on the top screen (display 0, 1920x1080).
- A live companion runs on the bottom screen (display 4, 1240x1080 logical, FLAG_PRESENTATION).
- The Play install is never modified. The host loads the game's code and data from it.

### Done
1. Pulled the APK splits to `apk/` (all except the 3 GB StreamingAssets pack). Extracted libil2cpp and the metadata to `extract/`.
2. Ran Il2CppDumper into `dump/`, with the user's approval.
3. Confirmed there's no licence check or PairIP. `gpdeku` is the Play Games overlay.
4. Installed the toolchain in `C:\Users\EvtraiPC\Android`:
   - `jdk17` (Temurin 17.0.21)
   - `sdk` with cmdline-tools/latest, platform-tools r37, platforms;android-35, build-tools;35.0.1, ndk;27.3.13750724 (r27d) and cmake;3.31.6
   - Set `JAVA_HOME=C:\Users\EvtraiPC\Android\jdk17` and `ANDROID_HOME=C:\Users\EvtraiPC\Android\sdk` before running Gradle.
   - Gradle is NOT installed yet. Plan: use a Gradle wrapper (distribution 8.x) with AGP 8.x.
5. Analysed Unity's Java startup (Unity **2022.3.62f2**, classic `com.unity3d.player.UnityPlayer`):
   - `new UnityPlayer(Context[, IUnityPlayerLifecycleEvents])` is a FrameLayout. It uses `context.getApplicationInfo().nativeLibraryDir`, does `System.load(dir+"/libmain.so")`, and libmain then loads libunity and libil2cpp from that dir.
   - It reads `unity.*` meta-data through `pm.getApplicationInfo(context.getPackageName(), META_DATA)`, so our own manifest must carry it:
     - unity.splash-mode=0
     - unity.splash-enable=true
     - unity.launch-fullscreen=true
     - unity.render-outside-safearea=false
     - unity.auto-report-fully-drawn=true
     - unityplayer.UnityActivity=true on the activity
   - It calls `getResources().getIdentifier(name, type, getPackageName())` for a few UI strings, so define those (for example `game_view_content_description`) in our own res.
   - The game's activity has launchMode=singleTask, screenOrientation=11 (fullUser), configChanges=0x40003fff, hardwareAccelerated and resizeable.
   - The dexdump of the game's Unity classes is `extract/unity_dexdump.txt`, made with build-tools dexdump.

### Planned design (spike, not built yet)
App folder: `C:\Users\EvtraiPC\SeaOfStarsDuo` (Gradle project at the root, module `app/`). Package `com.evtrai.sosduo`, minSdk 28, compile/target 35, arm64-v8a only.

`HostActivity` (no com.unity3d classes compiled in; everything goes through reflection):
- `gameCtx = createPackageContext("com.playdigious.seaofstars", CONTEXT_INCLUDE_CODE|CONTEXT_IGNORE_SECURITY)`. Its ClassLoader has the game dex plus its native lib dir, so the linker namespace permits /data/app/.../lib/arm64.
- Overrides:
  - `getClassLoader()` returns gameCtx's loader (needed for Unity's PermissionFragment and for JNI plugin lookups).
  - `getAssets()` returns gameCtx.getAssets(), which includes the splits.
  - `getApplicationInfo()` returns a copy of OUR ApplicationInfo with nativeLibraryDir, sourceDir, publicSourceDir, splitSourceDirs and splitPublicSourceDirs taken from the game.
  - `getPackageCodePath()` and `getPackageResourcePath()` return the game's base.apk.
  - Do NOT override getPackageName or getResources.
- Builds UnityPlayer by reflection, calls setContentView, and forwards lifecycle the way UnityPlayerActivity does:
  - onResume calls resume(), onPause calls pause(), onStart/onStop and onWindowFocusChanged are forwarded too.
  - onDestroy calls destroy(), onConfigurationChanged calls configurationChanged().
  - onNewIntent calls newIntent().
  - Key and generic-motion events go to injectEvent().
- A `Presentation` on the display from `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)` shows the companion UI.

`libsosduo.so` (NDK, C++), the native reader:
- Find libil2cpp's base and path through /proc/self/maps. Parse its .dynsym/.dynstr from the file on disk to resolve the `il2cpp_*` exports. That avoids dlopen across linker namespaces.
- Use these exports: il2cpp_domain_get, il2cpp_thread_attach, il2cpp_domain_get_assemblies, il2cpp_assembly_get_image, il2cpp_class_from_name, il2cpp_class_get_parent, il2cpp_class_get_field_from_name (it walks parents), il2cpp_field_get_offset, il2cpp_field_static_get_value and il2cpp_object_get_class.
- Read path: `CombatManager` parent (`Manager<CombatManager>`), then static `instance`, then `currentEncounter`, then `enemySlots` (List: `_items` array with data at +0x20, and `_size`), then `.enemy`:
  - `.castingData.turnsToAction`, `.totalSpellLocks` and `.spellLocks` (struct size 0x10, revealed at +0x8)
  - `.targets[0].currentHP`
- Poll at about 10 Hz from a thread attached to the il2cpp domain and push JSON or a struct to Java through JNI.

Saves: the host gets its own persistentDataPath, `/sdcard/Android/data/com.evtrai.sosduo/files`. Copy `SOS_*.sos` from the game's folder with adb. Ask the user first; it's their progress.

### Risks to check on device
- Unity native may resolve the APK or splits by package name instead of through the Context overrides.
- Firebase, Play Games and Helpshift init will fail under our package. Expect it to fail harmlessly, otherwise stub it.
- CONTEXT_INCLUDE_CODE oat/vdex use from a foreign uid might fall back to the interpreter, which is slow on the Java side only.
- Fallback plan if the host approach fails: a re-signed patched APK with the hook lib injected.

### SPIKE RESULT (2026-11-08): WORKS
The host app `com.evtrai.sosduo` boots Sea of Stars to its title screen on the top screen. The title shows "VERSION 0.1-SPIKE", which is our versionName. The bottom-screen Presentation is live, and the native reader resolves the il2cpp exports and finds the `CombatManager` singleton, showing "Exploring (no battle)".
- Project: Gradle project at the repo root, module `app/`; repackaged as a standalone project on 2026-11-14, with README, LICENSE (MIT), CI in .github/workflows/build.yml and docs/. Uses wrapper 8.11.1 and AGP 8.7.3. Build with JAVA_HOME and ANDROID_HOME set, then run `gradlew.bat assembleDebug`. A release build is `assembleRelease -PversionName=X -PversionCode=N`; it is signed with the debug key unless SOSDUO_KEYSTORE* env vars are set.
- Install with: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`. adb is `C:\Users\EvtraiPC\Android\sdk\platform-tools\adb.exe`.
- Screenshots: `adb exec-out screencap -p -d <physId>`. The top screen is 4630946441858561667 and the bottom is 4630946482288158084. Wake the device first with `input keyevent KEYCODE_WAKEUP`. A sleeping device pauses Unity and gives black frames.

Fixes the boot needed, in order. All of them are in the code now:
1. `unity.splash-enable=false`. Unity decodes `unity_static_splash` as a Bitmap, and our vector drawable caused an NPE.
2. Declare the Play Core components (AssetPackExtractionService, ExtractionForegroundService, PlayCoreDialogWrapperActivity) in OUR manifest, disabled. PAD calls setComponentEnabledSetting on the host package.
3. Override `getResources()` with a Resources object built on the game's AssetManager, with `getIdentifier` remapping our package name to the game's. Firebase resolves its options through R ids compiled into the game. Without this the game shows black frames forever, because it waits on Firebase.
4. Add a `com.google.android.gms.version` meta-data entry, plus Firebase's `ComponentDiscoveryService` with the game's 10 registrar meta-data entries. Without them the Crashlytics component is null and the native code aborts.
5. A dummy `com.crashlytics.android.build_id` in OUR res. Crashlytics reads it from the application context.
- Privacy: our manifest sets `firebase_analytics_collection_deactivated`, `firebase_crashlytics_collection_enabled=false`, `google_analytics_adid_collection_enabled=false` and `firebase_performance_collection_deactivated`. FA logs "No data will be collected". The Play asset-pack sync fails with -11, which is harmless because install-time packs are read from the splits.

### Still to do / next steps
- **Saves (done 2026-11-08, with the user's approval):** a verified backup (sha256 matches the device) is in `save-backup\20261108-101007`. The four SOS_*.sos files were copied into `/sdcard/Android/data/com.evtrai.sosduo/files` and chmod 660, because pushed files are owned by shell. It is NOT yet confirmed that they load in the host; the user is checking on the device.
- **UI direction the user chose:** a Chained Echoes-companion-style UI (github.com/igawa6/eden-duo-companions). That means tabbed pages (Battle / Party / Items / Quests), the game's own art and font loaded from the install, and native-looking pages. A plain text dump is "too simplistic".
- **Companion UI v1 (2026-11-08):** `CompanionView.java` uses custom-drawn widgets. Layout: header with area, gold and time. Tabs: BATTLE, PARTY, ITEMS.
  - Battle tab: enemy cards with a countdown octagon, lock icons per damage type and an HP bar, plus a combo-diamonds row and a party mini table with Live Mana orbs. It opens automatically when a fight starts and returns to the previous tab afterwards.
  - Party tab: coloured cards with name, title, level, HP/MP, ATK/DEF/M.ATK/M.DEF and equipment.
  - Items tab: category chips; tap a row to expand its description.
  - Verified on device: the party, items and no-battle tabs render with localized names (Valere / Lunar Monk etc.).
  - NOT yet verified with real enemies and locks; the user is going to get into a fight.
- **Reader data layer (`reader.cpp`):** produces a JSON snapshot every 200 ms; items refresh every 3 s. The fields are listed in snapshot(). Pitfalls that cost time:
  - `il2cpp_class_array_element_size` takes the ELEMENT class, not the array class. Use `elemSize(arrObj)`.
  - LocalizationManager lives in namespace `Sabotage.Localization`, NOT in Assembly-CSharp's global namespace. `findClass` now accepts qualified names and searches every image.
  - Localized text then goes through `replaceVariableLocalizers[i].ReplaceVariables(ref string)`, because character names are variables like MOONGIRL_NAME.
  - Combo points are stored in units of 1000 (COMBO_POINT_FILL_AMOUNT).
  - Party level comes from `CharacterStatsManager.GetLevel(false, 0)`. GetStat(Level) returns 0.
  - Items: iterate `allInventoryItemData` and call `GetOwnedQuantity(ref)` on each.
- Known issues and TODO:
  - Play time shows 0:01. `ProgressionManager.playTime` may be session-only, so find the real total.
  - Gold showed 0 on an early save (unverified).
  - No Quests tab yet; no quest data found yet.
  - No game art yet (portraits and icons). That needs a main-thread hook and texture readback; the idea is to patch MethodInfo->methodPointer of `InputManager.Update`, which has not been tested.
  - Remove the "snapshot" logcat line from HostActivity when done debugging.
- Test loop: build, `adb install -r` (this restarts the game), launch, wait 45 s, tap (960,680) on the title screen, wait 8 s, tap Continue at (960,520), wait 40 s. Then screenshot the bottom screen (-d 4630946482288158084) and tap it with `input -d 4 tap x y`.
- **Git (2026-11-08):** this folder is a LOCAL git repo on branch main, with no remote. Commit after every working step. .gitignore excludes the game-derived and large data: apk/, extract/, dump/, save-backup/, shots/ and the Il2CppDumper binary. If those are lost, re-pull the APK from the Thor and re-run Il2CppDumper (see "Done" above).
- **Main-thread layer (works):** `installMainThreadHook()` patches `MethodInfo->methodPointer` of `InputManager.Update` (global namespace) to `hookedUpdate`, which calls the original and then `mainTick()`. The log line "main-thread tick running" confirms it.
  - Portraits: `CharacterDefinition.miniPortrait` Sprite, then Blit into a RenderTexture, ReadPixels, then GetPixels32. The result is 24x24 RGBA, rows bottom-up. They look slightly washed out; try GraphicsFormat 8 (UNorm) instead of 4 (SRGB).
  - Touch commands: `activeList()` scans the current encounter state's fields for a `CombatActionSelectorListContent` (or the `...Touch` variant). `menuJson` lists its items. A "pick:i" action does `NavigateToItem(i,false)` and then the item's `OnSubmit(BaseEventData null)`, falling back to `list.OnItemSubmitted`. "back" calls `list.OnBack()`.
  - The command panel shows on device (state EncounterPlayerSelectBattleCommandState). Whether a pick actually advances the game is UNVERIFIED: the first try with OnItemSubmitted logged success but the game menu did not move.
  - Lock: `g_api` (recursive_mutex). The reader thread holds it per snapshot; mainTick only uses try_lock.
- Git-Bash gotcha: set `MSYS_NO_PATHCONV=1` before adb commands that use /sdcard paths, otherwise they get rewritten to C:/Program Files/Git/...
- Test controller input. The Presentation is FLAG_NOT_FOCUSABLE so the gamepad stays on the game. Touch on the top screen also needs testing.
- Get into a battle and verify the reader's enemy lines (HP, turnsToAction, locks). Then find the combo points owner (dump.cs:133012) and party HP.
- Possibly switch to a release build: debug builds enable CheckJNI, which is stricter and slower. A release build needs signing, and the debug key is fine for personal use.
- Replace the text companion with a real UI: battle panel, map and party.

## THOR EDITION (2026-11-16) — read PLAN.md first
The user called v1 "pretty mediocre" and wants a native-feeling dual-screen build (concept: docs/mockup/concept.png,
mockup: docs/mockup/thor-edition.html, published at https://claude.ai/artifact/8NYyZDFQDKyM32qPQy7hiA). Full plan and
research findings: PLAN.md ("RESUME HERE" line at the top).
- Phase 0 probe is built but UNTESTED and uncommitted: `adb devices` showed the Thor as `unauthorized`.
- To run it: create an empty `/sdcard/Android/data/com.evtrai.sosduo/files/duo_probe.txt`, install the debug APK, load a save,
  then `adb logcat -s SosDuo | grep -E "probe|displayChanged|engine surface"` and screenshot the bottom screen.
  Magenta showing through the dimmed companion = Unity renders on display 1. Delete duo_probe.txt to turn the probe off.

### Thor Edition status, 2026-11-16 evening (uncommitted; user has not asked for a commit)
Working on device (shots/v4_b.png, v4_t.png):
- `DuoView.java` replaces CompanionView on the bottom screen: 413x360 logical canvas at 3x, drawn with the game's fonts
  (`font_copy1056-webfontBitmap` = main, `font_kroegerBitmap` = small; alpha threshold 150) and sprites from
  `files/duocache/` (filled at runtime by `cache:scan`; sprite names listed in PLAN.md research / extract/duodump sheets).
- Charted map: cells within 2.25 units of the leader (`PlayerPartyManager.leader` transform, sampled on the main tick),
  saved per level guid in `files/duomaps/`. No walkability test yet, so it draws a trail blob, not room shapes.
- Top HUD hiding (`hud:off`, SetActive(false) on InGameHud panels) is OFF by default: applied at battle start it left the encounter stuck in EncounterIdleState (no command menu; `hud:on` did not recover it, a restart did). Opt in with
  `files/duo_hidehud.txt` for experiments. Next attempt: fade the panels (CanvasGroup alpha / move off-screen) instead of deactivating.
- Engine layer (display 1 camera) is kept but off (`AREA_ZOOM = 0`): the live zoomed view read as a copy of the top screen.
- Rule learned: do not touch Unity (camera, FindObjectsOfType) before the player is in the field. Doing it at boot made
  the game skip the title menu and start the new-game intro. Saves were checked afterwards: unchanged.
- Debug channel: write lines to `files/duo_cmd.txt` (`hud:on|off`, `map:<zoom>`, `cache:scan`, `dump:ui`, `probe:*`, `java:*`).
Next: walkability sampling for real room shapes + markers (save point, camp, chests, exits), battle board check in a
real fight with locks, objective strip, then menus on the engine layer (Phase 4).

### Battle board status, 2026-11-16 night (uncommitted)
User direction: battle screen first; "show exactly what the game is displaying"; only show things the game has unlocked;
batch changes and do NOT reinstall (it restarts the game) until the user says so.
- Test save: a downloaded Lv 9 save is installed as slot 2 (third slot); the user's own slots 0/1/99 untouched, backup in
  save-backup/20261116-device. Saves are a varint length prefix + plain JSON.
- `battleExtras()` (main thread, every 18 ticks) -> snapshot `bx`: per-character unlocked moves with MP cost and lock types
  (game's own IsUnlocked/CanBeShown/CanBeUsed/GetMPCost/GetLocksDamageTypes), KO stars, field Live Mana, ult points,
  comboShown/ultShown (game's own widgets active), showHP (ShowEnemyHPModifier relic), target under the cursor.
  Verified live: skill types, costs, target index, skills list with descriptions. NOT verified: lock rows + "who breaks"
  lines (no casting enemy seen yet), KO, Live Mana count, combo list. Basic-attack lock types throw for Valere/Zale
  (exception now logged with class + message; fallback GetDamageDamageTypes added).
- Greyed commands: read the label's TMP m_fontColor (the game does not flag them non-interactable).
- Built but NOT installed (19:39 build): game-style restyle (party panels 110x26 stepped 4 px, blue when acting, outlined
  numbers; list with title tab / 15 px rows / cost box; description bar with EFFECT icons; colours sampled from
  shots/v11_t.png) and `faces` (portrait sprite name read from the game's HUD widgets per character).
- `java:snap` debug command writes the current snapshot to files/duo_snap.json.

### Exploration status, 2026-11-16 late (uncommitted; installed build 20:24)
- Dungeon map: `scan:nav r` / `scan:level r` (NavMesh.SamplePosition grid, 0.5 u cells, heights) every 3 s / 30 s -> duo_scan.json ->
  DuoView merges into `cells` (files/duomaps/<level>.map), plus walked ground (navmesh has holes), drawn as one bitmap scrolled
  under the party; current storey only. Markers: save points, camps, exits (LevelLoadingZone + destination name), doorways
  (InLevelTeleporter "TEL_*" with teleportPosition; dotted path only for hops <= 35 units). Rooms are separate islands in world
  space; the gaps between them are real. Chests deliberately not shown (spoiler; game gates treasure info behind the parrot relic).
- Objective panel: ActivityManager main activity name + first sub-activity objective level name.
- Overworld (`wm` flag = leader is a WorldMap controller): map window is a transparent hole onto the engine layer; second camera
  at 2.4x zoom, shifted so the party is centred in the window (`map:<zoom> <ox> <oy>`). Rendering it every frame ran the Thor hot
  (UnityMain/Gfx ~66/70% vs 44/33% off). Now on for 4 consecutive frames in every 30 (must fill every swapchain buffer or the
  window flickers to black); measured ~54/35%, no black frames in 10 captures.
- Dungeon camera render is useless (dark, occluder masks). Wide overworld zoom (>~3x) shows the sea plane's edge and sea floor.
- Top HUD hiding: `hud:fade 0` / `hud:show` (CanvasGroup alpha, widgets stay active). Built in, NOT yet tried in a fight.
- Not yet verified on device: doorway marks/short dotted hops, key-cap prompts, exact info-bar descriptions, list paging.
- Next (user-agreed): quick command bar, whole-level view + floor switch, objective direction, full-area scan with faint unvisited rooms.

## CHECKPOINT 2026-11-16 21:00 — READ THIS FIRST (session ended on a time limit)
Installed on the Thor = last build (20:55). Everything is UNCOMMITTED in the working tree (user has not asked for a commit).
Working and seen on device:
- Battle board (game-styled lists, info bar text, banners with key caps, combo plate fill, ult frame, locks as closed doors
  until revealed, KO, greyed "acted" portraits, BOOST prompt, 6-row paging). Battle refresh 20/s.
- Dungeon map from navmesh scans + walked ground, save/camp/exit markers, doorway marks; objective panel.
- Overworld: the game's illustrated chart (WorldMap1/2 backgrounds + location sprites + fog pieces from `scan:worldmap`),
  current island name as title, party marker (offset +92,+17 calibrated on Watcher Island only), drag to pan / tap to select.
- Sectioned layout: OBJECTIVE / COMMANDS / PARTY boxes; party aligned; reserve members compact (game has 6 chars, 3 fight).
- Fast boot: Time.timeScale 8 until TitleSequenceManager exists (title in ~12 s). Opt out: files/duo_nofastboot.txt.
BROKEN / NEXT:
1. COMMANDS buttons do nothing: `pressTool` finds no UI Button ("press inventoryBtn: not available"). The game's touch buttons
   are Rewired TouchButtons (SOSTouchButton.touchButton), not UnityEngine.UI.Button. Fix: MAP -> call
   `InGameTouchUI.OnMapButton()` directly; menu -> find what consumes `PlayerInputs.GetGameMenuDown()` (action
   "Player_GameMenu", id 24) and open GameMenu the same way, then `GameMenu.ChangeSection(id)` (2 Characters, 3 Inventory, 4 Relics).
   **2026-11-17: fix written and BUILT (debug APK 16:22), NOT installed/tested (Thor was not on adb).** `pressTool` now runs
   the game's own open sequence, read from `InGameHud.ProcessInputs` (0x2738054) with capstone:
   menu = `CanOpenAnyPanel` && `UIManager.IsGameMenuEnabled` -> `SinglePlayerPlusManager.SetUIInputsPlayer(InputManager.FirstPlayer)`
   -> `UIManager.InstantiatePreloadedScreen(typeof(GameMenu), 3, null, true, 2)` -> `Animator.Update(0)` on view + home section;
   map = `CanOpenAnyPanel` -> `StreamingWorldMapManager.TryOpenWorldMapScreen(player)` (the touch `OnMapButton` has no
   guards and uses layer 3 instead of 1; switch to it if the map opens behind something). CAMP button removed (`tools.camp`
   always false): camping is a held input (`CampingDeployPartyTrigger.Update` / `PlayerWorldMapState.ProcessInputs`), no
   single call. To test: install, load slot 2, tap PARTY/ITEMS/RELICS/MAP, `adb logcat -s SosDuo | grep -E "menu|map:"`.
   Tools for this kind of lookup: %TEMP%\callers.py / calls.py / a64dis.py were throwaway (BL scan of libil2cpp.so, RVA = file offset + 0x4000).
2. Unverified on device: smooth marker fit (anchors), dungeon doorway fix, sectioned DUNGEON layout (only overworld seen).
3. What the gold star on the game's map is (player vs objective cursor): `scan:worldmap` now dumps `cursors`; open the game's
   map (top-screen touch: 4-dot button -> map) and read files/duo_worldmap.json.
4. Not built: whole-level dungeon view + floor switch, "this area" progress (parrot relic), objective arrow.
5. Top HUD hide by transparency exists (`hud:fade 0` / `hud:show` via files/duo_cmd.txt) but was never tried in a fight.
6. No quest log exists in the game; do not add one.
User rules: batch changes, minimise restarts, show only what the game shows/unlocked, watch heat, no live camera window.
Test save = slot 2 (third); user's own saves untouched (backup save-backup/20261116-device).

## CHECKPOINT 2026-11-17 16:45 — READ THIS FIRST (supersedes the 11-16 checkpoint's item 1)
Installed on the Thor = build 16:59 (see the 17:00 section at the end). User said 11-17: "Restart and load as needed" (reinstalls/restarts are fine while they are hands off). Everything is still UNCOMMITTED (user has not asked for a commit).
User request (11-17, hands-off session): build what another dev sketched for his companion (exploration menus on the bottom
screen: equip, items, party order, relics, skills, with real changes "without going into the menu in game"), keep our map,
match our UI. So the bottom screen may now CHANGE the game, but only through the game's own calls and only on a tap.

### Companion menus (works on device, all verified on the slot-2 test save)
- Quick bar (COMMANDS) = PARTY / ITEMS / RELICS / MAP. First three open bottom-screen tabs (`DuoView.openTab`, tab bar
  BACK / PARTY / ITEMS / RELICS); MAP opens the game's own world map on the top screen. CAMP removed (hold input in the game).
- PARTY: party list in order (RESERVE tagged), CHANGE ORDER (tap two), character card with stats, EQUIP (slot -> choices
  with stat change preview -> EQUIP / TAKE OFF for trinkets) and SKILLS (cost, description, USE for field skills).
- ITEMS: food first then key items, gear, ingredients, recipes; description + effect text; USE -> pick a party member.
- RELICS: list with ON/OFF switch (tap the switch), description on the right.
- Data: native `buildMenuData()` -> files/duo_menu.json on "md:refresh" (asked every 2.5 s while a tab is open) and after
  every action; snapshot carries `mdSeq`. Equip choices: "md:equip <char> <slot>" fills `choices`.
- Actions ("do:..." in reader.cpp `doMenu`), each guarded by `menuAllowed()` = not in battle, game menu not open,
  `InGameHud.CanOpenAnyPanel` && `UIManager.IsGameMenuEnabled`:
  - `do:equip <char> <slot> <guid|->`: `PlayableCharacterData.SetEquippedWeapon/SetEquippedArmor/EquipCharacterTrinket(int,
    CharacterTrinket)/RemoveCharacterTrinket/EquipGroupTrinket/RemoveGroupTrinket` (what
    `GameMenuCharacterSectionEquipmentPanel.EquipSelectionPanel_OnItemEquippedCallback` calls). Choices = owned gear of the
    slot's class with `CanEquipToCharacter` and owned - worn > 0 (same rule as `InventoryManager.GetAvailableEquipmentForCharacter`).
  - `do:swap <a> <b>`: copy of `GameMenuCharacterPreviewPanel.CompleteChangeOrder`: SPP `UnassignAllCharacters`, table(+0x118)
    `ClearLastAssignedCharacterByPlayer`; Zale<->Valere = `SwapPartyLeader` + Unregister/RegisterLeader, else
    `SwapCharacterOrderInParty(a, b, true)`; then `AssignCharactersToPlayers(true, true)`. `FixPopJumpIssues` NOT replicated.
  - `do:relic <guid>`: `RelicManager.ToggleRelic(RelicItem)` if `CanBeEnabled`.
  - `do:use <guid> <char>`: `ConsumableItem.CanConsume` -> `Consume(target)` -> `InventoryManager.RemoveInventoryItem(ref, 1)`
    (`InventoryPreviewPanel.SubmitConsumeItem`). Refused with "It would have no effect" when the target (or whole party for
    isAOE food) is at full HP and MP - our own guard, the game may not have it.
  - `do:skill <char> <moveIdx> <target>`: copy of `...SkillTargetSelectionPanel.UseSkillOnTarget` (MP cost, heal capped at max HP,
    AOE = whole currentParty). Same full-HP guard.
- Verified live: armor equip (stats changed by the game 24->37), Garl<->Serai swap, Zale/Valere leader swap (top screen
  followed), relic on/off, Lunar Shield (MP 15->4), food consumed, no-effect guard, MAP opens the game's map.
- Debug: `java:tab <0-3>` in files/duo_cmd.txt opens a tab; bottom-screen taps via `input -d 4 tap x y` (logical px * 3).
- Research tools kept: `tools/cls.py <Class...>` (fields + methods from dump.cs), `tools/fn.py "<Class :: signature text>"`
  (annotated ARM64 disassembly of a method; needs `pip install capstone`, RVA = file offset + 0x4000).

### Known gaps / next
1. (fixed 16:47 build) List rows are 22 px so the game's 21 px icons draw at real size; the footer line was only hidden by the toast.
2. Verified 16:48-16:51: trinket take off + equip (Dextrous Bangle Serai -> Valere); Healing Light on a hurt Valere
   (20 -> 52 HP, Zale MP 12 -> 8); Tomato Club +20 HP; Hearty Stew on a 0-HP Garl (-> 90); Mushroom Soup on the party
   (capped at max, stack removed at 0). HP was lowered with the adb-only test aid `md:dbghp <CHAR> <hp>` (duo_cmd.txt).
   Not tried: group trinket (none owned), actions during a cutscene, swap while standing on a ledge (FixPopJumpIssues).
3. Gold star on the game's map = the party's position (seen 11-17 with the map opened from a dungeon).
4. Still open from 11-16: marker fit, doorway fix, sectioned dungeon layout check, whole-level view + floor switch,
   parrot-relic area progress, objective arrow, top HUD fade in a fight (`hud:fade 0`).
5. The Thor sleeps after its screen timeout; a build installed/launched while it is asleep boots to a black screen with no
   "main-thread hook" log. Wake first (`input keyevent KEYCODE_WAKEUP`); `svc power stayon usb` while testing, `false` after.
Saves: backup of all slots before this session in save-backup/20261117-before-menus. Tests ran on slot 2 only.

### 2026-11-17 17:00 — navigation redesign (installed build 16:59, uncommitted)
User: the COMMANDS bar on the map plus the tab bar after tapping were "redundant and not in the style".
- Now ONE list, always at the top-left (`drawNav`, 6,30,150x108): Map / Equip / Skills / Items / Order / Relics = the game's own
  menu list (Equip, Skills, Items, Order, Relics; seen in shots/g3_home.png) with Map in front; game selection bar
  (`selRow`: bright blue edge, gradient) + HandCursor overlapping the panel's left edge. Header shows the game-style hint
  line while a menu is open ("View stats and change equipment." and "View skills and combos." are the game's texts; the
  other three are mine).
- Left column under the list: map view = OBJECTIVE (y 144) + PARTY HUD (y 200); menu views = party rows (also the target /
  swap picker). Content panel = CX 160, CW 247, CT 30 (`drawCharTab` for Equip+Skills, `drawItemsTab`, `drawOrderTab`,
  `drawRelicsTab`), lists with the game's column-title band (`band`), no row plates, 22 px rows.
- "WORLD MAP >" on the dungeon map's title line opens the game's map on the top screen (only when the game offers it).
- Overworld chart now uses the same window as the dungeon map (MAP_X/MAP_Y/MAP_W/MAP_H) so the left column is identical
  everywhere. NOT seen on device yet (test save is inside Torment Peak): check marker position, pan/tap and fog there.
- `do:swap` now also runs the game's `FixPopJumpIssues` (never reads `this`, so it is called with a stand-in object). Verified:
  Zale<->Serai and Valere<->Serai swaps, game kept running, top screen followed.
- REMOVED from reach: `openGameMenu` / "menu:<n>" / press:inventoryBtn. `InstantiatePreloadedScreen(typeof(GameMenu), ...)`
  returned null and left a half-initialised menu (all sections, placeholder French text) on the top screen; only a restart
  cleared it. Do not revive without the generic-method path.
Still open: group trinket (none owned), HUD fade in a fight, overworld check above, and the 11-16 map items.
- 17:05 (build 17:04 installed): header LEVEL badge = party level + XP fill (snapshot `plv`, `xpRem`, `xpReq` from CharacterStatsManager.GetLevel / GetXPRemainingToNextLevel / GetXPRequiredToLevelUp; the game widget is GameMenuTopBar.uiXPBar). Seen on device: LEVEL 9, 192/800.
- 17:20 (build 17:19 installed): LEVEL badge now uses the game's own layers: 80x20, `experience-xp-bar-mobile-progress` (68x18 gradient, full bar width, clipped at the XP fraction) + `experience-xp-bar-mobile-stars` (201x18 strip scrolling 0.05 widths/s = UIXPBar.backgroundScrollSpeed; `java:xps <v>` tunes it). Compared side by side with the game: shots/z_cmp.png. Both sprites only get cached while the game's own menu is open (cache:scan reads active Images), so a fresh install shows a flat blue fill until then. Exploration redraw went from 4 to 10 pictures/s for the star motion (battle stays 4): watch heat.
- 17:30 (build 17:29 installed): user said the menu was too thin to tap. Nav is now a 2x3 grid of 72x30 cells (box 6,30,150x96); action buttons 64x22, BACK 62x18, bigger hit areas on WORLD MAP and the relic switch. Left column moved up (objective y 132, map-view party y 190, menu-view party section y 134). Clock hidden while a menu is open (the game's bar has none) so the hint line fits beside the LEVEL badge.
- 17:40 (build 17:36 installed): OVERWORLD CHECKED on device (Watcher Island): chart sits in the map window, left column intact. Gold star = OBJECTIVE, not the party (scan:worldmap with the game map open: cursors `map-icn-objective` r 384,88 and player `map-icn-valere` r 369,82; they flash in turn). scanWorldMap now writes `obj` per section = each MapObjective of ActivityManager.GetSubActivity().activityObjectives through the section`s TryGetMapLevelPosition (custom positions only on the current section), since the game`s star cursors exist only while its map is open. DuoView draws `map-icn-objective` there, alternating with the head when they overlap. Marker offset re-measured against the star: wmDx 90, wmDy 30, both markers centre-anchored (old 92/17 had been fitted to the star as if it were the party). Still off: on the overworld our head uses the live fitted position (me 365,99) while the game puts its head at 369,82 via PlacePositionCursorUsingBounds, so ours sits ~17 px higher. To reach the overworld from the test save: two `input swipe 400 600 400 850 5000` (walk south out of Torment Peak).

### 2026-11-17 18:00 — checks with a completed save (build 17:58 installed; test save restored afterwards)
SPOILER RULE (user, 11-17): a 100%-complete save is in Downloads (`SOS_SaveSlot1.sos`, 310 KB) for testing, "but don't let
me spoil myself with it". So: never name its characters, places, items or story in anything the user reads (replies, this
file, commit messages); keep its screenshots in `shots/SPOILERS-100pct/` only; always restore the Lv 9 test save before
handing the Thor back. The patched copy (slot 2, newer timestamp) is `save-backup/full100/SOS_SaveSlot2.sos`.
- How to swap it in: force-stop; push `save-backup/full100/SOS_SaveSlot2.sos` + `full100/SOS_SaveDataIndex.sos` (no autosave
  entry), delete `Autosave_SOS_SaveSlot2.sos`, `chmod 660`; Continue = (960,483) (the title gains a fourth button).
  Restore: push the three files from `save-backup/20261117-1740-before-100pct/`, `chmod 660`; Continue = (960,519).
- GOTCHA: "Continue" loads the save with the newest internal `timestamp`. The download's was older than the user's own
  beginning-of-game slots, so Continue started THEIR slot (opening scenes). Stopped without saving; all slot checksums
  still match the backup. The patched copy has its timestamp raised (same digit count, varint prefix unchanged).
- GOTCHA: `svc power stayon usb` did not keep the Thor awake here; the 60 s screen timeout put it to sleep mid-test (black
  boot, "App backgrounded"). Use `settings put system screen_off_timeout 1800000` while testing and put 60000 back.
- Found and fixed with it:
  1. Chart markers were only right on the first chart. Each place button now also dumps its region's spot in cursor
     coordinates (`p` = MapLocationRegion.mainMapPosition); DuoView fits the offset per chart from all buttons (median;
     logged once per chart as "chart fit ..."). Second chart: a 114, b 295 (the old constants were 24 / 72 px off); head
     lands where the game's map shows it. First chart fit: a 92, b 372.5 vs my hand measurement 90 / 367.
  2. Characters the Lv 9 save never showed had no portrait, and the map head was always the first hero. The snapshot
     now carries each member's `face` (CharacterDefinition.combatPortrait) and `mapIcon`, queued for the sprite cache.
  3. Item category "SheetMusicItem" got its own label.
- Verified at scale: 5-member party (3 + 2 reserve) in every screen, 147 items with drag scrolling, 16 relics, 8-10
  skills per character, group trinket equip, LEVEL badge at a higher level, second world chart.
- Not checked: the planet-selection and third charts, a 6+ member party, max level badge (`SetMaxLevel`).
- 18:35 (build 18:33 installed): Equip overview is now the game`s card (`drawEquipCard`): big portrait (CharacterDefinition.defaultPortrait.get_Asset -> snapshot `portrait`, fallback any cached `dialog-portrait-<id>`), gold-tinted `portrait-background-temp`, name plate, title, stats column (Level/HP/MP/ATK/DEF/M. ATK/M. DEF), slots below. The slot-choice list and Skills keep the compact header so their lists have room. Party layouts adapt: left list pitch = min(31, 204/n); Order reserve column tightens past 4; map-view reserve lines shrink past 3. `java:pad N` repeats the last member up to N for layout tests; checked with 7 (shots/y_all.png): nothing overflows.
- 18:42 (build 18:40 installed): user spotted the Equip card showing a different portrait than the game. The card must use `CharacterDefinitionManager.GetPortrait(id)` (what GameMenuCharacterSectionCharacterInfo.RefreshCharacterInfo calls), not `defaultPortrait.get_Asset` (that gave the front-facing "neutral" art). Fixed; snapshot `portrait` now e.g. dialog-portrait-valere, same as the game`s card (shots/z67.png).
- 18:55 (build 18:53 installed): "only what is available at the time". What is enforced, each with the game's own check:
  skills = CanBeShown + IsUnlocked; equip choices = owned, CanEquipToCharacter, a free copy; items/relics = owned; relic
  switch = CanBeEnabled; USE = CanConsume / IsUsableOutOfCombat + MP; WORLD MAP link = the game's map button is offered;
  overworld chart = map item owned, fog as the game has it; every action = menuAllowed().
  NEW: nav entries follow GameMenuHomeSection.SetHomeState: Relics greyed until RelicManager.IsPlayerOwningRelics, Order
  greyed unless GameMenuHomeSection.CanChangeOrder() (run with a stand-in `this`; it never reads it), all menu entries
  greyed on the map view while the game would not open its menu (`ok`). `do:swap` is refused when CanChangeOrder is false.
  Menu data (`relicsOk`, `orderOk`, `ok`) is now refreshed every 6 s on the map view too. On the test save all three are true.
  NOT covered: the game also hides some menu buttons through per-button GameObjectActivator conditions (only readable from
  its open menu prefab), so an entry the game hides early on could still show here. Greyed states have not been seen on
  device (needs an early-game state; the user's own slots are at the very start and must not be played).

### 2026-11-17 19:20 — relic screen matched to the game; new-game availability test (build 19:18 installed)
- Game's Relics screen (shots/rel_all.png): lists ONLY owned relics (unobtained ones are not shown at all), highest
  `orderPriority` first, slider switch art `relic-switch-mobile-on/off`, the panel shows GetFullDescriptionText (what it
  does) and the top bar the short description; screen hint "Browse and toggle relics.". Ours now does the same.
- New-game test (user's idea: New Game, get past the opening, finish the first battle). How: title New Game (960,672) ->
  pick hero (690,540) twice -> difficulty (960,552) twice -> a long press skips the text crawl, the opening video cannot
  be skipped (~4 min), tap through dialogs -> scripted battle: attack = (1744,904) then CONFIRM same spot; then the skill
  button (1640,660) -> list entry (1315,622) -> confirm; then the combo button (1500,790) -> entry -> confirm twice.
  NEW GAME WRITES INTO SOS_SaveSlot99.sos AND THE INDEX (it overwrote the user's slot 99): back up first, restore after.
  Restored from save-backup/20261117-1900-before-newgame; all checksums match.
- Result at the first free-roam moment (level 1, three members, 0:00): the game's menu shows all five entries, none
  greyed; seven relics are already owned (the difficulty ones); its drawer has no map button. Ours agreed: relicsOk /
  orderOk true, WORLD MAP link absent, nav greyed while the game's own menu was open. Only mismatch: the game shows
  gold "0", ours hid the purse (fixed: no purse entry = 0). So a greyed Relics/Order entry is still unseen on device.
- Gotcha: `export MSYS_NO_PATHCONV=1` before `gradlew.bat` breaks JAVA_HOME; run gradle first, export after.
- 19:45: TOP HUD FADE TRIED IN A FIGHT (new-game tutorial battle, shots/hf_all.png): `hud:fade 0` removes the party panels
  and the combo badge from the top screen (the touch buttons stay); an attack then went through normally (enemy
  countdown 2 -> 1, next character's turn) and the tutorial's script carried on. `hud:show` brings them back. So the
  CanvasGroup-alpha approach does not stall the encounter the way SetActive(false) did. Not yet seen with it on: an open
  skill/combo/item list on the top screen, a full fight to the victory screen, a boss.
  User (19:30): not loving the bottom screen's battle background (the tiled save-slot texture) - asked whether there is a
  better one, to be looked at after the current work. Candidates: the game's night-sky gradient with stars (its New Game
  and difficulty screens), plain game-menu navy with corner ornaments, parchment like the map.
  Saves restored again from save-backup/20261117-1900-before-newgame (checksums match).
- 20:05 (build 20:00 installed): backdrops are selectable for comparison, `java:bg N` (0 tiled save-slot texture = current
  default, 1 night sky with stars, 2 plain menu navy, 3 parchment; `drawBackdrop`). Comparison in a real fight sent to the
  user: shots/battle_backgrounds.png. Waiting for their pick; then make it the default and drop the others.
- ORIGINAL Lv 9 SAVE POSITION RESTORED at the user's request ("that had a fight"): slot 2 = save-backup/lv9orig/
  SOS_SaveSlot2.sos (the 11-16 download, timestamp raised so Continue picks it), index without autosave, the Torment Peak
  autosave deleted from the device (still in save-backup/20261117-1900-before-newgame). It starts on Watcher Island's
  overworld at 8:06. The fight: enter Torment Peak (walk up to the cave, nudge the stick, press the action button
  (1794,970)), then one `input swipe 400 750 400 500 4000` north -> battle with two enemies. The game writes a new
  autosave on entering, so Continue lands inside the cave again afterwards; re-push lv9orig to get the start back.
  The Thor was left IN that battle (command list open) at 20:05.
- 20:30 (build 20:25 installed): "TOP SCREEN HUD" switch on the battle view (`drawHudSwitch`, top-left, 140x24, game's
  slider art). Off = `hud:fade 0` re-sent every 1.5 s while a battle is on, `hud:show` when the battle ends or the switch
  goes back on; choice kept in files/duo_tophud_hidden. NOT SEEN ON DEVICE: after the install I could not get back into
  the fight. Correction to the 20:05 note: I did NOT walk into that battle. Location entry on the overworld needs a
  controller confirm press (stick swipes, taps on the entrance, the action drawer and `input keyevent BUTTON_A` all do
  nothing); the user was holding the Thor and entered the cave and the fight themselves while I was sending swipes.
  Lesson: when the user says they are playing, do not drive the game over adb at the same time and do not reinstall
  without asking - the install here threw them out of their battle.
- 20:45: user picked background B (night sky). Switched live on the Thor (`java:bg 1`, no restart) and made it the default in code (`bgMode = 1`); that build is NOT installed yet (install restarts the game; ask first, the user may be playing). Until then a restart of the app falls back to the tiles.
- 21:00 (build 20:55 installed, night sky default): TOP HUD switch SEEN WORKING in the Torment Peak fight
  (shots/top_hud_toggle.png): off = party panels, combo badge and the swap portrait vanish from the top screen, touch
  buttons stay; on = back. User wanted it "minor, like a setting": now a dim "TOP HUD" tag with a dot at the bottom
  screen's top-left (6,28), dot cyan = on. Tap area (2..~70, 26..48 logical) = real (90,110). Left OFF on the Thor
  (files/duo_tophud_hidden exists), battle still open.
- To reach that fight without a controller: Continue must load the cave AUTOSAVE (push save-backup/adv/SOS_SaveSlot2.sos
  = original timestamp, so the autosave is the newest; cave autosave backed up in save-backup/20261117-2050-cave-autosave),
  then five `input swipe 400 750 400 500 4000` north from (27.5, 3.5) -> battle at about z 78.
- 21:40 (build 21:35 installed): with the top HUD hidden an open touch list left an empty framed box on the top screen.
  The list chain is SpecialMoveSelectorTouch < Content < Mask < BackgroundContent < BackgroundVisual < GenericBackground <
  Container; fadeHud now also fades the "GenericBackground" ancestor (this takes the list's back button with it; the
  hand cursor still shows). Every CanvasGroup made transparent is remembered (`g_fadedGroups`) and `hud:show` restores
  all of them (`unfadeAll`), so a list closed while hidden is not left invisible. Seen on device: hidden -> list only on
  the bottom; switch back on -> list, frame, panels all return (shots/bh_all.png). Collage v2: shots/overview_collage_v2.png.
  Thor left in the Torment Peak battle, skill list open, top HUD ON.

### 2026-11-17 late — rough edges, camp, top-HUD rule (build 22:35 installed)
- TOP HUD RULE (user): "the UI hides only what the bottom screen handles, nothing else, and by hide we mean transparent".
  fadeHud now fades the party panels (PlayerBattleUI.playerPanelsList) and the combo badge (comboPointsPanel) one by one
  instead of the whole PlayerBattleUI, so the character switcher stays; a list's frame ("GenericBackground") is faded
  but its "BackBtn" child gets its own CanvasGroup with ignoreParentGroups (`keepBackButtons`), so it stays. Seen in the
  Torment Peak fight: hidden = no panels/combo/list/frame, switcher and back button visible; back on = all restored.
  Still visible in hidden mode: the game's hand cursor (it also points at targets in the scene, so it is left alone).
- CAMP (tested from the overworld's campfire button): snapshot `camp` = CampingManager.isCamping. At camp the bottom keeps
  the island chart and marker as they were on arriving (no rescans, no charting of the camp scene). The game's own menu at
  camp greys Order and lists ONLY the leader; ours now does both (`present` per member = PlayerPartyManager.GetPartyCharacter
  active; `atCamp()` filters on it, at camp only: on the overworld a reserve member is not "present" yet the game lists her).
  This is also the first on-device confirmation of the greyed Order entry (shots/e_campmenu_s.png vs ours).
- Party marker on the overworld = where the game's own map puts it: each scan points the hidden MapScreen at the party's
  chart (currentContext, restored right after), runs its PlacePositionCursor and reads the cursor (`pc` in
  duo_worldmap.json; skipped while the game's map is open or in battle). Scan every 3 s on the overworld.
  The same scan writes a world-position based `fit`; it disagreed with the hand-checked placement (70/328 vs 90/367 on
  chart 1), so the per-chart button median stays in use and `fit` is ignored.
- Character sheet: the game-style portrait card is now on Equip, on the slot-choice list and on Skills (`drawCard`); the
  duplicate "LEVEL n" is gone; lists below show five rows and scroll.
- Not done: copying which menu entries the game hides by story conditions (would go stale because the bottom menus
  replace the game's menu); star on chart 1 remains ~5 px lower than the hand measurement.

### 2026-11-17 night — remaining rough edges (build 23:20 installed)
- Hand cursor: with the top HUD hidden, a cursor whose target is inside the hidden list is made transparent
  (UICursorManager.activeCursorStacks -> UICursor.target, Transform.IsChildOf the list frame); pointing at anything else
  it stays. Seen in the fight: hidden list = no cursor, back button stays; switched on = cursor back.
- List frame for any layout: the outermost of the run of "...Background..." ancestors is faded (was the literal name
  "GenericBackground"); the chain is logged once per list class as "list chain (...)". The CONTROLLER's command list is
  still unseen with the HUD hidden (adb cannot press controller buttons) - check that log line when the user tries it.
- Exact chart offset: measured from the middle of the game's cursor picture WHILE THE GAME'S MAP IS OPEN (`fitLive` in
  duo_worldmap.json), saved per chart in files/duo_fit_<chart>.txt and used from then on. Chart 1: 92 / 371 (the button
  median gave 92 / 372.5, the old hand value 90 / 367). Measuring on the hidden map screen is NOT reliable (70 / 328, and
  its party cursor 396,78 vs the live 367,90), so `pc` is ignored and the party head is back to the live-position fit.
- Tried and dropped: reading which home-menu entries the game hides (GameObjectActivator conditions). The game destroys
  its menu object on closing, so there is nothing to ask afterwards; in every state reached (new game, Lv 9, camp, late
  game) the game showed all five entries anyway.
- Removed: `md:dbghp`, `java:bg` and the three unused backdrops. Kept: `java:pad N`, `java:tab N`, `java:xps`, `java:snap`.

### 2026-11-18 — two bugs reported by the user after playing (build installed)
- Skills screen mixed skills and combos. Now titled groups in one list: SKILLS (cat 1), COMBOS (cat 3, cost drawn as combo-point diamonds from `cp` = comboPointCost), ULTIMATE (cat 2); MP costs read "n MP".
- "Opening the menu locks the bottom screen": with the GAME's own menu open, every nav entry was greyed (menuAllowed false). Menu data now carries `gameMenu`; the nav stays usable for browsing, and a change is refused with "Close the game's menu first". My reading of the report; confirm with the user that this was the lock they meant.

### 2026-11-18 — "weird button stuck at the top right" (user report, cause not reproduced before the fix)
- Likely cause: the list back button that `keepVisible` told to ignore its panel (ignoreParentGroups) kept ignoring it after the list closed, the battle ended or the HUD was switched back on, so the game could no longer hide it with the panel. Now tracked (`g_keptGroups`, `g_keptFor`) and released (`releaseKept`) as soon as that list is not the open one, when the HUD is shown again, and outside battle (hudFaces passes the live list). Seen after the fix: HUD hidden, list opened then closed -> button gone. If the user still sees a stuck button, get a photo: it is something else.

### 2026-11-18 — top-screen button prompts showed a "missing button" picture (user report, fixed, build installed)
- Seen: "Hold _ for power!" on the top screen while the bottom drew the A key cap. NOT caused by the bottom screen: Rewired
  knows the Thor's pad as "Odin Controller" (hardware id AndroidFallbackOdinController) with buttons named "Button 1..4",
  "L1", "R2", "Select"...; the prompt text is `<sprite="dialog-generic-inputs-template-mobile-controller" name="Button 1">`
  and that sheet only has "A", "B", "Left Shoulder"..., so TMP fell back to the "MissingButtons" sheet (15x15 "no input").
- Fix `fixPadGlyphs` (main thread, every 300 ticks because the game reloads the sheet): adds the pad's names to the sheet's
  character table, each pointing at the existing glyph, then UpdateLookupTables. Table checked against the game's own
  bindings: Button 1 = Interact/confirm (A, user-confirmed), 2 = Cancel (B), 3 = bracelet/secondary (X), 4 = menu (Y),
  L1/R1 = shoulders, L2/R2 = triggers, Select = Back. Log: "pad glyphs: 9 names added".
- Debug: `probe:pad` (pad element names + action bindings + lookup check), `probe:sprite` (what the battle boxes' inline
  sprite is drawn from). Top-screen taps over adb switch the game to its touch controller, so the prompt cannot be
  reproduced over adb; the A glyph on the first fix build was seen by the user ("Looks like we got something").
- Lettered buttons (user asked the same evening: "make the top one use the A button like bottom"): the Thor is labelled
  like an Xbox pad, so the generic sheet's PICTURE is exchanged for the game's own Xbox sheet (material.mainTexture +
  spriteSheet, plus rect/metrics of same-named glyphs; 24 cells). Battle starts and plays; user: "that worked better".
  Opt out: files/duo_nolettered.txt. DO NOT point the generic sheet's glyph/character tables at the Xbox sheet's lists:
  that froze the battle in EncounterIdleState at its start (no error logged), reverted.
  Not covered: stand-alone button icons (RemappableSpriteButton images, e.g. BOOST) still use the generic set.
