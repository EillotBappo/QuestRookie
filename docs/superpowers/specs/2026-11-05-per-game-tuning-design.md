# Per-game tuning dialog

Date: 2026-11-05
Status: approved design, not yet planned

## Goal

Let the user tune one game's headset settings directly, in the style of Quest Games Optimizer, instead of
setting the global tweaks and then copying them into the game from the `⋮` menu.

This is the first of three UI pieces. The other two (a device dashboard home screen and Store front-page
polish) get their own designs later.

## What exists today

- `TWEAKS` in `Tweaks.kt` lists every headset tweak (refresh rate, render resolution, foveation, dynamic
  foveation, CPU/GPU level, recording quality) with its chip options.
- `TweakStore` keeps global choices in the `tweaks` preferences and per-game profiles in `profile_<pkg>`.
  `applyForLaunch` uses the game's profile when it has one, otherwise the global choices.
- `AppState.launch` and the auto-profile switching in `Automation.kt` already call `applyForLaunch`.
- The only way to create a profile is "Save current tweaks for this game" in the `⋮` menu (`Device.kt`),
  which snapshots the global choices. "Clear game tweaks" deletes it.
- Game rows (`AppRow`) and gallery tiles (`AppTile`) in My Games have no tap action of their own.

## Design

### Opening the dialog

Tapping a game's row in list view, or its tile in gallery view, opens the dialog. Only entries with
`a.isGame` open it; other apps keep their current behaviour.

### Dialog contents

An `AlertDialog`, matching the existing Pair dialog:

- Title: the game's name.
- The last session's frame stats from `Automation.lastPerf`, when there are any.
- One line stating the source: "Using your global tweaks" when the game has no profile, otherwise
  "This game's own tweaks".
- One compact row per entry in `TWEAKS`: the tweak's label and its options as `QChip`s in a `FlowRow`. The
  hint text is left out so the dialog fits without much scrolling. The selected chip shows the value from
  `TweakStore.getFor`.

### Buttons

- **Launch** (confirm slot): shown when the app is launchable and not disabled. It calls `s.launch(pkg)`,
  which applies the profile before starting the game, and closes the dialog.
- **Use global** (dismiss slot): shown only when the game has a profile. It calls `TweakStore.clearProfile`
  and the chips switch to the global values.
- When the game is not launchable, the confirm slot shows **Close**.
- Tapping outside the dialog closes it.

### Saving

Each chip tap saves immediately; there is no Save button.

- `TweakStore.getFor(ctx, pkg, t)`: the profile's value when the game has a profile, otherwise the global
  value.
- `TweakStore.setFor(ctx, pkg, t, v)`: writes the profile computed by `profileWith` (below). On the game's
  first change this copies the current global choices into the profile, then applies the change.
- Pure helper `profileWith(profile: Map<String, String>?, globals: Map<String, String>, key: String,
  value: String): Map<String, String>` returns `(profile ?: globals) + (key to value)`.

Saving only writes preferences. It does not run anything over ADB, so it works while ADB is disconnected.
The change takes effect the next time the game launches, through `s.launch` or auto-profile switching.

### Menu cleanup

Remove "Save current tweaks for this game" and "Clear game tweaks" from the `⋮` menu in `Device.kt`. The
dialog replaces both. The menu item is `TweakStore.saveProfile`'s only caller, so `saveProfile` is removed
too; `setFor` does the copying instead.

## Files

- `app/src/main/java/com/questrookie/Tweaks.kt`: `profileWith`, `TweakStore.getFor`, `TweakStore.setFor`.
- `app/src/main/java/com/questrookie/Device.kt`: the dialog, row and tile tap handling, menu cleanup.
- `app/src/test/java/com/questrookie/TweakProfileTest.kt`: JVM test for `profileWith`.

## Testing

- `TweakProfileTest` covers `profileWith`: no profile copies the globals and applies the change; an
  existing profile keeps its other values and ignores the globals; setting a key back to "" (Default) is
  stored as "".
- There is no Robolectric in the project, so the `SharedPreferences` code is not unit-tested. Check it by
  hand on the emulator: open the dialog, change a chip, close and reopen it, and confirm the choice stuck
  and the global Settings screen did not change.

## Out of scope

- Applying changes while the game is already running.
- Presets inside the dialog.
- A "Tuned" badge on rows or tiles of games that have a profile.

