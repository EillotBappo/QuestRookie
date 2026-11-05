# QuestRookie Store Redesign — Phase 1

Date: 2026-10-29 · Status: awaiting review

## Problem

The app works but reads as a beta:

- Library opens on ~4,900 titles sorted A–Z; the first screen is F-Droid phone apps ("0x54ask", "10-bit Clock Widget").
- Square app icons are cropped into 16:9 tiles; hundreds of tiles are initials placeholders; the featured banner is a GitHub avatar blown up.
- Developer scaffolding is visible: Public Apps Store cycles through dead test endpoints (one is `localhost:3000`); raw exceptions appear in the bottom status line (`Unable to resolve host "t.me"`); the logo is the text "QR".
- No flow: state, source and 18+ chips share one row; a game opens in a popup; progress is a thin bar at the very bottom; Installed and Storage list the same apps.

## Decisions (agreed with the user)

| Question | Decision |
|---|---|
| What the app opens on | Store-style home: curated rows, full grid behind "Browse all" |
| F-Droid | Still loaded, but never in home rows; only in Browse all and search |
| Navigation | 4 rail tabs: Store · My games · Downloads · Settings |
| Game page | Full page, not a popup |
| Build approach | **B — restyle in place**: rows go above the grid in `Library.kt`; the game page is a full-screen `Dialog` |
| Descriptions & screenshots | Done (commit `d2f5294`); the new game page reuses them |

Phase 2 (separate spec): redesign the *contents* of My games, Downloads and Settings.

## 1. Shell (approved)

**Rail** (`MainActivity.kt`, `Section` enum):

| Tab | Icon | Phase 1 content |
|---|---|---|
| Store | gamepad | `LibraryScreen` (sections 2–5) |
| My games | grid (new `QIcons.Apps`) | Segmented switch `Installed · Storage · Local files` over the existing `InstalledScreen` / `StorageScreen` / `LocalScreen` |
| Downloads | download + orange count badge (active + queued jobs) | `DownloadsScreen` unchanged |
| Settings | gear, pinned to the rail bottom | `TweaksScreen` plus a **Sources** card (the Sources dialog content), a **Shortcuts** row and the **Hide 18+** switch |

B / Back still returns to Store; Y still cycles tabs.

**Logo**: the "QR" text tile becomes a vector mark (stylised Q with a play notch) in the existing orange gradient, drawn from path data in `Ui.kt` like `QIcons`.

**Rail status**: battery % with a battery glyph, and an ADB glyph labelled "Connected" / "Not connected" (replaces the unlabelled dot).

**Feedback**:
- The bottom `status` line is removed. `AppState.task` results go to a `SnackbarHostState` shown at the bottom of the content area: 4 s for results, errors stay until dismissed and offer **Retry** when the task can be rerun.
- Errors pass through one function, `friendlyError(Throwable): String`:
  - `UnknownHostException` / `ConnectException` → "No internet connection"
  - mirror HTTP 401/403/404 → "Mirror unavailable — showing your saved list"
  - `SocketTimeoutException` → "The server took too long to answer"
  - anything else → the exception message, first line only, never a stack trace.
- While `busy != null`, a spinner and the busy label (e.g. "Downloading F-Droid list") sit at the top of the content area, above every screen's header (one place instead of six).

## 2. Store home (`Library.kt`)

**Home mode** = no search text, no filter, no non-default source. It shows, top to bottom:

1. Header: "Store", subtitle "4,935 titles · updated 5 h ago"; actions **Refresh** and **⋮** (Surprise me). Sort/Select move to Browse.
2. Search field, full width (max 640 dp).
3. **Featured hero** — the newest not-installed title with a good thumbnail (§4: a mirror thumbnail or F-Droid feature graphic; never F-Droid here, so in practice a mirror thumbnail) (checked off the main thread with a bounds-only decode, `inJustDecodeBounds`; first 30 candidates by `updated`). Catalog icons are never candidates, so a GitHub avatar is never stretched into a banner. Hidden when none qualifies (e.g. no mirror).
4. **Rows** (horizontal, 200 dp cards, "See all" on the right). A row is hidden when empty:

| Row | Content (never F-Droid) | See all → Browse with |
|---|---|---|
| Updates for you | state = Update | filter Updates |
| New & updated | by `updated` desc, top 20 | sort Recently updated |
| Popular | `popularity` desc (mirror only) | sort Popular |
| Top rated | `rating` desc, `ratingCount` ≥ 5 | sort Top rated |
| VR from SideQuest | source SideQuest | source SideQuest |
| Open-source picks | source GitHub | source GitHub |
| Your favourites | `pkg in favourites` | filter Favourites |

5. "Browse all" header and the grid (section 3).

Typing a search or choosing any filter/source switches to **browse mode**: rows and hero hide, the grid shows results.

Row selection lives in one pure function, `homeRows(games, stateOf, favourites): List<HomeRow>`, so it is unit-tested without Compose. Rows are full-span items inside the existing `LazyVerticalGrid` (a horizontal `LazyRow` inside a vertical grid is supported).

## 3. Browse all (grid + filters)

One filter row, left to right:
- **Sort ▾** — default **Recommended**: non-F-Droid titles first, then F-Droid, each newest-updated first. Name, Popular, Top rated, Recently updated, Largest, Last played stay available.
- **Source ▾** — dropdown (All, Mirror, SideQuest, GitHub, F-Droid, extra sources) replacing the four source chips.
- Toggle chips **Installed · Updates · Favourites** (no "All" chip; nothing selected = all).
- **Select** (multi-install) moves here from the header.

**Hide 18+** moves to Settings (a preference, not a filter). The scroll-to-hide behaviour from `d37a9fd` stays.

## 4. Cards and art

**Rule: a good thumbnail wins; otherwise the app icon; otherwise a letter.** Each title can carry both a thumbnail and an icon:

| Source | Thumbnail (wide) | Icon |
|---|---|---|
| Mirror | local thumbnail file | OculusDB cover (720×720 square) when there is no thumbnail |
| F-Droid | `featureGraphic` (1024×500), ~60 % of apps | `icon` |
| SideQuest | — | `icon` (512×512) |
| GitHub | — | owner avatar |

- `Game` gains `thumbUrl` (cached as `"t"` in the catalog game list); `parseIndexV1` keeps the en-US (else first) `featureGraphic` as `<repo>/<pkg>/<locale>/<file>`.
- A thumbnail counts as **good** when the decoded bitmap is landscape and big enough: `isGoodThumbnail(w, h) = w >= 400 && w / h >= 1.3`. It then fills the 16:9 card area and the game page hero.
- If there is no thumbnail, or it fails that check or fails to load, the **icon treatment** is used: the icon at 72 dp with rounded corners and a soft shadow, centred on a blurred, darkened copy of itself. Nothing is cropped.
- **No art at all**: the same icon layout with a neutral rounded square and the first letter, so placeholders match real icons.
- The featured hero (§2) only ever uses a good thumbnail.

**Card text**: title (1 line), then `source · size` and ★ rating when present. Raw version codes (`v1015`, `v202608001`) leave the card; the page shows the version.

**Badges**: Installed / Update top-left, download progress along the bottom edge (unchanged behaviour).

**Focus**: controller focus scales the card to 1.04 with the orange ring.

## 5. Game page (full-screen `Dialog`)

`GameDetail` becomes a full-screen dialog wrapped in `Scaled`, opening over the Store:

1. **Top bar**: ← Back (also B / system Back), title.
2. **Hero** (16:9, max 360 dp tall): landscape art or OculusDB cover; otherwise the icon treatment from §4. Trailer button on the hero.
3. **Title block**: icon, name, publisher or source; on the right the primary button — Install / Update / Launch / Cancel, with download progress inside the button — and a heart for Favourite. Initial focus lands on the primary button.
4. **Facts row**: ★ rating (count) · size · version · "Updated 3 days ago" · source.
5. **About**: summary, expandable description, genre chips.
6. **Screenshots** strip + full-screen viewer (built in `d2f5294`).
7. **What's new** (release notes / mirror notes).
8. **More like this**: same source and a shared genre, else same source newest; 10 cards; opening one replaces the page.
9. **Technical**: package name, release name — small text at the bottom.

## 6. Cleanup

- Delete `PublicAppStoreScreen`, `PublicAppCard`, `AppMetadataFetcher.kt` and `AppState.publicApps` / `refreshPublicApps` (both endpoints are dead: 404 and non-existent host).
- Remove the raw status `Text` in `MainActivity`.
- Empty and error states use plain wording with a single next action.

## Testing

- **Unit** (`app/src/test`): `homeRows` (F-Droid excluded, empty rows dropped, ordering), `friendlyError` (each mapping), `isGoodThumbnail`, feature-graphic parsing, Recommended sort order.
- **Emulator pass**: screenshot every tab, the home with and without mirror data, Browse with each filter, a game page for each source (F-Droid, SideQuest, GitHub), the screenshot viewer.
- **Controller pass**: D-pad/B/Y through rail → home rows → game page → back, using `adb shell input keyevent`.
- **Startup**: cold start must not regress (row computation happens off the main thread, as the catalog load does today).

## Out of scope

- Content redesign of My games, Downloads, Settings (phase 2).
- Fixing the mirror's HTTP 403 (separate issue).
- Games-vs-apps classification (lists don't carry a reliable category).
