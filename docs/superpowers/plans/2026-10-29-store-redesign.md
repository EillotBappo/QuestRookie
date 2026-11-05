# Store Redesign (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn QuestRookie's Library into a store-style home with a 4-tab shell, honest art, plain-language feedback and a full-screen game page.

**Architecture:** Approach B from the spec — restyle in place: `Library.kt` keeps the Store screen (rows above the existing grid) and the game page becomes a full-screen `Dialog`. Pure, testable logic goes in one new file, `StoreLogic.kt`, because `Ui.kt`/`Library.kt` load Android classes (e.g. `android.util.LruCache`) that JVM unit tests can't initialise.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.12.01: foundation 1.7, material3 1.3), JUnit 4, org.json (test), Gson streaming reader.

## Global Constraints

- No new dependencies (spec decision; icons come from Material path data like `QIcons`).
- F-Droid titles never appear in home rows or the featured hero; they appear in Browse all and search.
- Art rule: a good thumbnail wins (`w >= 400 && w / h >= 1.3`); otherwise the app icon on a blurred copy of itself; otherwise a letter.
- Controller support stays: B/Back returns to Store, Y cycles tabs, focus rings on every focusable.
- Every task ends with: unit tests pass, `assembleDebug` succeeds, install on emulator, screenshot reviewed, commit.
- Commands (run from the repo root):
  - Tests: `rtk ./gradlew :app:testDebugUnitTest -q`
  - Build: `rtk ./gradlew :app:assembleDebug -q`
  - Install + launch: `adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am force-stop com.questrookie && adb shell cmd package resolve-activity --brief com.questrookie | tail -1 | xargs adb shell am start -n`
  - Screenshot: `adb exec-out screencap -p > $SCRATCH/<name>.png`

---

### Task 1: Friendly errors, snackbar feedback, dead Public Apps Store removed

**Files:**
- Create: `app/src/main/java/com/questrookie/StoreLogic.kt`
- Create: `app/src/test/java/com/questrookie/StoreLogicTest.kt`
- Modify: `app/src/main/java/com/questrookie/AppState.kt` (`task`, new `retry`, remove `publicApps`/`refreshPublicApps`/`fetcher`)
- Modify: `app/src/main/java/com/questrookie/MainActivity.kt` (snackbar host, busy bar at the top of the content area, remove status `Text` and bottom progress bar)
- Modify: `app/src/main/java/com/questrookie/Library.kt` (`refresh` summary wording; delete `showPublicApps`, `PublicAppStoreScreen`, `PublicAppCard`, menu item)
- Delete: `app/src/main/java/com/questrookie/AppMetadataFetcher.kt`

**Interfaces:**
- Produces: `fun friendlyError(e: Throwable): String`; `AppState.retry: (() -> Unit)?`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.questrookie

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreLogicTest {
    @Test
    fun friendlyErrorsReadAsPlainLanguage() {
        assertEquals("No internet connection", friendlyError(java.net.UnknownHostException("t.me")))
        assertEquals("No internet connection", friendlyError(java.net.ConnectException("refused")))
        assertEquals("The server took too long to answer", friendlyError(java.net.SocketTimeoutException()))
        assertEquals("The server refused access (HTTP 403)", friendlyError(IllegalStateException("HTTP 403 for https://example.com/meta.7z")))
        assertEquals("Not found on the server (HTTP 404)", friendlyError(IllegalStateException("HTTP 404 for https://x")))
        assertEquals("The server had a problem (HTTP 502)", friendlyError(IllegalStateException("HTTP 502 for https://x")))
        assertEquals("Disk full", friendlyError(RuntimeException("Disk full\n\tat com.x.Y")))
        assertEquals("RuntimeException", friendlyError(RuntimeException()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — `rtk ./gradlew :app:testDebugUnitTest -q` → `Unresolved reference 'friendlyError'`.

- [ ] **Step 3: Implement `StoreLogic.kt`**

```kotlin
package com.questrookie

// Pure store logic: no Android classes, so it runs in plain JVM unit tests.

/** One short sentence for a failure, never a stack trace. Mirror/HTTP errors arrive as "HTTP <code> for <url>". */
fun friendlyError(e: Throwable): String {
    val msg = e.message.orEmpty()
    val code = Regex("""^HTTP (\d{3})""").find(msg)?.groupValues?.get(1)?.toInt()
    return when {
        e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "No internet connection"
        e is java.net.SocketTimeoutException -> "The server took too long to answer"
        code == 401 || code == 403 -> "The server refused access (HTTP $code)"
        code == 404 -> "Not found on the server (HTTP 404)"
        code != null -> "The server had a problem (HTTP $code)"
        else -> msg.lineSequence().first().trim().ifEmpty { e.javaClass.simpleName }
    }
}
```

- [ ] **Step 4: Run tests** — expect all pass.

- [ ] **Step 5: `AppState.task` reports friendly errors and remembers a retry**

Replace `task` in `AppState.kt` with:

```kotlin
    /** Rerun of the last failed task; the snackbar offers Retry while it's set. */
    var retry by mutableStateOf<(() -> Unit)?>(null)

    /** Runs one foreground job off the main thread, reporting its result in [status]. */
    fun task(label: String, block: suspend () -> String) = scope.launch {
        busy = label
        retry = null
        status = try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { retry = { task(label, block) }; friendlyError(e) }
        busy = null
    }
```

Delete `fetcher`, `publicApps`, `refreshPublicApps` and the `AppMetadataFetcher` import from `AppState.kt`; delete `AppMetadataFetcher.kt`.

- [ ] **Step 6: Snackbar + busy bar in `MainActivity.kt`**

Inside `App`, before `Scaffold`:

```kotlin
    val snackbar = remember { SnackbarHostState() }
    // Each new status becomes a snackbar; failures stay until dismissed and offer Retry.
    LaunchedEffect(s.status) {
        if (s.status.isEmpty()) return@LaunchedEffect
        val retry = s.retry
        val result = snackbar.showSnackbar(
            s.status, actionLabel = if (retry != null) "Retry" else null, withDismissAction = retry != null,
            duration = if (retry != null) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) retry?.invoke()
        s.status = ""
    }
```

`Scaffold(snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface, actionColor = MaterialTheme.colorScheme.primary, dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant) } }, …)`.

In the content `Column`, before the screen `Box`:

```kotlin
                // What's running right now, at the top of the page instead of a bare bar at the bottom.
                s.busy?.let { label ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
```

Delete the bottom `LinearProgressIndicator` and the `s.status` `Text`.

- [ ] **Step 7: `refresh` summary wording in `Library.kt`**

```kotlin
    val failed = mutableListOf<String>()
    …
        }.getOrElse { failed += "${src.label} unavailable (${friendlyError(it)}), showing your saved list"; src.games() }
    …
    "%,d titles".format(s.games.size) + failed.joinToString("") { " · $it" }
```

Delete `showPublicApps`, the early return into `PublicAppStoreScreen`, the "Public Apps Store" menu item, `PublicAppStoreScreen`, `PublicAppCard`, and now-unused imports (`BitmapFactory`).

- [ ] **Step 8: Verify** — tests, build, install; tap Refresh with the mirror failing → snackbar reads "4,935 titles · Mirror unavailable (The server refused access (HTTP 403)), showing your saved list"; no text line at the bottom; ⋮ has no Public Apps Store.

- [ ] **Step 9: Commit** — `git commit -am "Plain-language snackbars, busy bar, remove dead Public Apps Store"`

---

### Task 2: 4-tab rail, logo, rail status, My games and Settings hosts

**Files:**
- Modify: `MainActivity.kt` (`Section` enum, rail, routing)
- Modify: `Ui.kt` (`QIcons.Apps`, `QIcons.Usb`, `QIcons.Battery`; `Logo` drawn mark)
- Modify: `Device.kt` (new `MyGamesScreen`)
- Modify: `Tweaks.kt` (header "Settings"; new cards: Sources, Content (Hide 18+), Shortcuts)
- Modify: `Library.kt` (`SourcesDialog` becomes `internal`; remove Sources/Shortcuts from ⋮)

**Interfaces:**
- Consumes: `SourcesDialog(s: AppState, onClose: () -> Unit)` (now `internal`)
- Produces: `MyGamesScreen(s: AppState)`, `Section { Store, MyGames, Downloads, Settings }`

- [ ] **Step 1: Icons** — add to `QIcons`:

```kotlin
    val Apps = icon("Apps", "M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z")
    val Battery = icon("Battery", "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z")
    val Usb = icon("Usb", "M15,7v4h1v2h-3V5h2l-3,-4 -3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93 0,-1.21 -0.99,-2.2 -2.2,-2.2 -1.21,0 -2.2,0.99 -2.2,2.2 0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95 0,1.22 0.99,2.2 2.2,2.2 1.21,0 2.2,-0.98 2.2,-2.2 0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2h1V7h-4z")
```

- [ ] **Step 2: Logo** — replace the `Text("QR")` in `Logo` with a drawn mark:

```kotlin
    ) {
        // A play triangle inside a ring with a tail: a "Q" that reads as "play".
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize(.62f)) {
            val w = size.minDimension
            val stroke = w * .13f
            drawCircle(Ink, radius = w / 2 - stroke / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawLine(Ink, Offset(w * .68f, w * .68f), Offset(w * .98f, w * .98f), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * .41f, w * .32f); lineTo(w * .41f, w * .68f); lineTo(w * .7f, w * .5f); close() }, Ink)
        }
    }
```

- [ ] **Step 3: Sections and rail** (`MainActivity.kt`)

```kotlin
private enum class Section(val label: String, val icon: ImageVector) {
    Store("Store", QIcons.Gamepad),
    MyGames("My games", QIcons.Apps),
    Downloads("Downloads", QIcons.Download),
    Settings("Settings", Icons.Default.Settings),
}
```

Rail header: logo, then two status rows — `QIcons.Battery` 14 dp + "92%" (existing colour rules), and `QIcons.Usb` 14 dp + "Connected"/"Not connected" (`Green` / `outline`). Rail items: Store, My games, Downloads (icon wrapped in `BadgedBox` with `Badge(containerColor = primary) { Text("$n") }` when `s.queue.activeCount > 0`) inside the scrolling column; Settings after the column so it pins to the bottom. Routing:

```kotlin
                        Section.Store -> LibraryScreen(s)
                        Section.MyGames -> MyGamesScreen(s)
                        Section.Downloads -> DownloadsScreen(s)
                        Section.Settings -> TweaksScreen(s)
```

`BackHandler`/B go to `Section.Store`.

- [ ] **Step 4: `MyGamesScreen`** (`Device.kt`)

```kotlin
/** Installed apps, storage and APK files on the headset, behind one switch. Phase 2 merges them into one page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyGamesScreen(s: AppState) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = listOf("Installed", "Storage", "Local files")
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.padding(top = Space.lg).widthIn(max = 480.dp)) {
            pages.forEachIndexed { i, label ->
                SegmentedButton(
                    page == i, { page = i }, SegmentedButtonDefaults.itemShape(i, pages.size), Modifier.focusRing(RoundedCornerShape(Radius.pill)),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary, activeContentColor = MaterialTheme.colorScheme.onPrimary),
                ) { Text(label) }
            }
        }
        when (page) { 0 -> InstalledScreen(s); 1 -> StorageScreen(s); else -> LocalScreen(s) }
    }
}
```

- [ ] **Step 5: Settings** (`Tweaks.kt`) — header `ScreenHeader("Settings", "Headset tweaks reset on reboot and are re-applied when the app starts.", compact = true)`; grid items before the tweaks:

```kotlin
            item(key = "sources") { SourcesCard(s) }
            item(key = "content") { ContentCard(s) }
            item(key = "uisize") { UiSizeCard(s) }
```

```kotlin
/** Where games come from; opens the Sources editor. */
@Composable
private fun SourcesCard(s: AppState) {
    var editing by remember { mutableStateOf(false) }
    SettingsCard("Sources", "Mirror" + (if (s.rookie.mirrors.isEmpty()) " (not set up)" else "") + ", " + (CATALOGS.map { it.name } + s.extras.map { it.label }).joinToString()) {
        FilledTonalButton({ editing = true }, Modifier.focusRing(RoundedCornerShape(Radius.pill))) { Text("Manage sources") }
        FilledTonalButton({ Shortcuts.start(s.ctx); Shortcuts.openInBrowser(s.ctx) }, Modifier.focusRing(RoundedCornerShape(Radius.pill))) { Text("Shortcuts") }
    }
    if (editing) SourcesDialog(s) { editing = false }
}

/** Content preferences that used to sit among the Library filters. */
@Composable
private fun ContentCard(s: AppState) {
    SettingsCard("Content", "Hide titles marked 18+ everywhere in the store.") {
        Switch(s.hideAdult, { s.chooseHideAdult(it) }, Modifier.focusRing(RoundedCornerShape(Radius.pill)))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(title: String, hint: String, content: @Composable () -> Unit) {
    Card(Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}
```

`UiSizeCard` switches to `SettingsCard`. In `Library.kt` remove the Sources and Shortcuts menu items and `editSources`; make `SourcesDialog` `internal`.

- [ ] **Step 6: Verify** — build, install; screenshot rail (4 tabs, badge when queueing), logo, My games switch, Settings with Sources/Content/App size cards; D-pad: `adb shell input keyevent KEYCODE_BUTTON_Y` cycles tabs, `KEYCODE_BUTTON_B` returns to Store.

- [ ] **Step 7: Commit** — `git commit -am "4-tab rail, drawn logo, rail status, My games and Settings hosts"`

---

### Task 3: Thumbnail-first art and cleaner cards

**Files:**
- Modify: `Rookie.kt` (`Game.thumbUrl`)
- Modify: `Catalogs.kt` (`parseIndexV1` keeps `featureGraphic`; cache field `"t"`)
- Modify: `StoreLogic.kt` (`isGoodThumbnail`)
- Modify: `Ui.kt` (`GameArt` rule, `IconTile`, `Modifier.focusScale`)
- Modify: `Library.kt` (`GameArt(s, g, …)` call sites, `GameCard` text)
- Tests: `CatalogsTest.kt`, `StoreLogicTest.kt`

**Interfaces:**
- Produces: `Game.thumbUrl: String`; `fun isGoodThumbnail(w: Int, h: Int): Boolean`; `@Composable fun GameArt(s: AppState, g: Game, modifier: Modifier = Modifier)`; `fun Modifier.focusScale(): Modifier`

- [ ] **Step 1: Failing tests**

`StoreLogicTest`:

```kotlin
    @Test
    fun onlyWideBigImagesAreGoodThumbnails() {
        assertTrue(isGoodThumbnail(1024, 500))
        assertTrue(isGoodThumbnail(640, 360))
        assertFalse(isGoodThumbnail(720, 720))   // OculusDB cover: square
        assertFalse(isGoodThumbnail(512, 512))   // SideQuest icon
        assertFalse(isGoodThumbnail(300, 150))   // wide but tiny
    }
```

`CatalogsTest` (extend the F-Droid fixture of `indexKeepsDescriptionAndScreenshotsAsDetails` with `"featureGraphic":"fg.png"` in `en-US`):

```kotlin
    @Test
    fun featureGraphicBecomesThumbnail() {
        val index = """
            {"repo":{"address":"https://f-droid.org/repo"},
             "apps":[{"packageName":"org.example","icon":"i.png","lastUpdated":1700000000000,
                      "localized":{"en-US":{"name":"Example","featureGraphic":"fg.png"}}}],
             "packages":{"org.example":[{"apkName":"e.apk","versionCode":5,"versionName":"1.5","size":1}]}}
        """.trimIndent()
        val g = parseIndexV1(index.reader(), "F-Droid", iconBase = "").single()
        assertEquals("https://f-droid.org/repo/org.example/en-US/fg.png", g.thumbUrl)
    }
```

- [ ] **Step 2: Run** — fails (`thumbUrl`, `isGoodThumbnail` unresolved).

- [ ] **Step 3: Implement**
  - `Game(... val iconUrl: String = "", val thumbUrl: String = "")` with KDoc "Wide banner art (F-Droid feature graphic); used instead of the icon when it's a good thumbnail."
  - `parseIndexV1`: in the localized loop add `"featureGraphic" -> r.nextString().let { if (en || feature.isEmpty()) feature = "$locale/$it" }`; store per app; in the result `thumbUrl = feature.takeIf { it.isNotEmpty() }?.let { base + pkg + "/" + it }.orEmpty()`.
  - `writeGames` adds `.put("t", g.thumbUrl)`; `readGames` reads `thumbUrl = j.optString("t")`.
  - `StoreLogic.kt`: `fun isGoodThumbnail(w: Int, h: Int) = w >= 400 && w >= h * 1.3f`
  - `Ui.kt`:

```kotlin
/** A title's art: a good wide thumbnail fills the area; otherwise its icon sits on a blurred copy of itself; otherwise a letter. */
@Composable
fun GameArt(s: AppState, g: Game, modifier: Modifier = Modifier) = GameArt(s.thumbnail(g), g.name, modifier, url = g.thumbUrl, icon = g.iconUrl)

@Composable
fun GameArt(file: File?, title: String, modifier: Modifier = Modifier, url: String = "", icon: String = "") {
    val thumb = rememberArt(file, url)
    if (thumb != null && isGoodThumbnail(thumb.width, thumb.height)) {
        Image(thumb, null, modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentScale = ContentScale.Crop)
        return
    }
    // A thumbnail that isn't wide (e.g. a square cover) still beats no icon.
    IconTile(thumb ?: rememberArt(null, icon), title, modifier)
}

@Composable
private fun IconTile(img: ImageBitmap?, title: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, null, Modifier.matchParentSize().blur(28.dp).graphicsLayer { alpha = .6f }, contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .1f), Color.Black.copy(alpha = .45f)))))
        val shape = RoundedCornerShape(22)
        Box(Modifier.fillMaxHeight(.56f).aspectRatio(1f).shadow(10.dp, shape).clip(shape).background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else Text(title.trim().take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** Grows a focused element slightly, so a controller user sees where they are beyond the ring. */
fun Modifier.focusScale(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.04f else 1f, label = "focusScale")
    onFocusChanged { focused = it.hasFocus }.graphicsLayer { scaleX = scale; scaleY = scale }
}
```

  - `Library.kt`: every `GameArt(s.thumbnail(g), g.name, url = g.iconUrl, modifier = M)` becomes `GameArt(s, g, M)`. `GameCard` gets `.focusScale()` before `.focusRing`, and its second line becomes `listOfNotNull(g.source, g.sizeLabel, if (g.rating > 0) "★ %.1f".format(g.rating) else null).joinToString(" · ")`.

- [ ] **Step 4: Run tests** — pass.

- [ ] **Step 5: Verify** — build, install, Refresh once (feature graphics enter the cache); screenshot Browse: F-Droid apps with feature graphics fill the card; others show centred icons on blur; no stretched icons; no `v202608001` on cards.

- [ ] **Step 6: Commit** — `git commit -am "Thumbnail-first art with icon tiles; cleaner card text; focus scale"`

---

### Task 4: Store home — hero and rows

**Files:**
- Modify: `StoreLogic.kt` (`latestPerTitle`, `HomeRow`, `homeRows`)
- Modify: `Library.kt` (home/browse modes, `ShelfRow`, `FeaturedHero`, header)
- Test: `StoreLogicTest.kt`

**Interfaces:**
- Produces:
  - `fun latestPerTitle(games: List<Game>): List<Game>` — newest version per `(source, pkg)`
  - `enum class RowKind { Updates, New, Popular, TopRated, SideQuest, OpenSource, Favourites }`
  - `class HomeRow(val kind: RowKind, val title: String, val games: List<Game>)`
  - `fun homeRows(games: List<Game>, stateOf: (Game) -> GameState, favourites: Set<String>, hideAdult: Boolean): List<HomeRow>`

- [ ] **Step 1: Failing tests**

```kotlin
    private fun g(name: String, source: String, updated: String = "2026-01-01", pop: Double = 0.0, rating: Double = 0.0, count: Int = 0) =
        Game(name, "$name v1 ($source)", "pkg.$name", "1", updated, "1.0", popularity = pop, source = source, rating = rating, ratingCount = count)

    @Test
    fun homeRowsSkipFDroidAndEmptyRows() {
        val games = listOf(
            g("Fd", "F-Droid", updated = "2026-12-31"),
            g("Sq", "SideQuest", updated = "2026-05-01"),
            g("Gh", "GitHub", updated = "2026-06-01"),
            g("Mx", "Mirror", pop = 9.0, rating = 4.5, count = 10),
            g("Low", "Mirror", rating = 5.0, count = 2),
        )
        val rows = homeRows(games, { if (it.name == "Mx") GameState.Update else GameState.NotInstalled }, favourites = emptySet(), hideAdult = true)
        assertTrue(rows.none { r -> r.games.any { it.source == "F-Droid" } })
        assertEquals(listOf(RowKind.Updates, RowKind.New, RowKind.Popular, RowKind.TopRated, RowKind.SideQuest, RowKind.OpenSource), rows.map { it.kind })
        assertEquals(listOf("Gh", "Sq", "Mx", "Low"), rows.first { it.kind == RowKind.New }.games.map { it.name })
        assertEquals(listOf("Mx"), rows.first { it.kind == RowKind.TopRated }.games.map { it.name })
    }

    @Test
    fun latestPerTitleKeepsNewestVersion() {
        val old = g("A", "Mirror").copy(version = "3")
        val new = old.copy(version = "10", release = "A v10")
        assertEquals(listOf(new), latestPerTitle(listOf(old, new)))
    }
```

- [ ] **Step 2: Run** — fails.

- [ ] **Step 3: Implement in `StoreLogic.kt`**

```kotlin
/** Lists carry several releases of one game; browsing shows the newest per source. */
fun latestPerTitle(games: List<Game>): List<Game> =
    games.groupBy { it.source to it.pkg }.values.map { l -> l.maxBy { it.version.toLongOrNull() ?: 0 } }

enum class RowKind { Updates, New, Popular, TopRated, SideQuest, OpenSource, Favourites }

class HomeRow(val kind: RowKind, val title: String, val games: List<Game>)

private const val ROW_SIZE = 20

/** The store home's rows, in display order. F-Droid's phone apps never appear here; empty rows are dropped. */
fun homeRows(games: List<Game>, stateOf: (Game) -> GameState, favourites: Set<String>, hideAdult: Boolean): List<HomeRow> {
    val pool = latestPerTitle(games).filter { it.source != FDroid.name && !(hideAdult && it.isAdult) }
    val newest = pool.sortedByDescending { it.updated }
    return listOf(
        HomeRow(RowKind.Updates, "Updates for you", pool.filter { stateOf(it) == GameState.Update }),
        HomeRow(RowKind.New, "New & updated", newest),
        HomeRow(RowKind.Popular, "Popular", pool.filter { it.popularity > 0 }.sortedByDescending { it.popularity }),
        HomeRow(RowKind.TopRated, "Top rated", pool.filter { it.rating > 0 && it.ratingCount >= 5 }.sortedWith(compareByDescending<Game> { it.rating }.thenByDescending { it.ratingCount })),
        HomeRow(RowKind.SideQuest, "VR from SideQuest", newest.filter { it.source == SideQuest.name }),
        HomeRow(RowKind.OpenSource, "Open-source picks", newest.filter { it.source == GitHubApps.name }),
        HomeRow(RowKind.Favourites, "Your favourites", newest.filter { it.pkg in favourites }),
    ).map { HomeRow(it.kind, it.title, it.games.take(ROW_SIZE)) }.filter { it.games.isNotEmpty() }
}
```

`LibraryScreen` uses `latestPerTitle(games)` in place of its inline `groupBy`.

- [ ] **Step 4: Run tests** — pass.

- [ ] **Step 5: Home UI in `Library.kt`**
  - State: `var browsing by rememberSaveable { mutableStateOf(false) }`; `val home = !browsing && query.isEmpty() && filter == null && source == "All" && !picking`; `BackHandler(enabled = browsing) { browsing = false; filter = null; source = "All"; sort = Sort.Recommended }` (Recommended lands in Task 5 — in this task use the current default).
  - Header: `ScreenHeader("Store", "%,d titles%s".format(shown.size, listAge))` with Update all, Refresh, ⋮ (Surprise me).
  - Search field full width `Modifier.fillMaxWidth().widthIn(max = 640.dp)` above everything; the filter row shows only when `!home`.
  - `rows = remember(games, versions, s.favourites, s.hideAdult) { homeRows(games, s::stateOf, s.favourites, s.hideAdult) }`.
  - Hero: `val hero by produceState<Game?>(null, games, versions) { value = withContext(Dispatchers.IO) { latestPerTitle(games).filter { it.source != FDroid.name && s.stateOf(it) == GameState.NotInstalled }.sortedByDescending { it.updated }.take(30).firstOrNull { g -> s.thumbnail(g)?.let { f -> BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }.let { o -> isGoodThumbnail(o.outWidth, o.outHeight) } } == true } } }`.
  - Grid content when `home`: `item(span = full) { FeaturedHero(s, hero) { open = it } }` when hero != null; `rows.forEach { r -> item(key = "row-${r.kind}", span = full) { ShelfRow(s, r, onSeeAll = { seeAll(r.kind) }, onOpen = { open = it }) } }`; `item(key = "browse-header", span = full) { Text("Browse all", style = titleLarge) }`; then the grid items.
  - `seeAll(kind)`: `browsing = true` and Updates → `filter = Filter.Updates`; New → `sort = Sort.Updated`; Popular → `sort = Sort.Popular`; TopRated → `sort = Sort.Rating`; SideQuest → `source = SideQuest.name`; OpenSource → `source = GitHubApps.name`; Favourites → `filter = Filter.Favourites`.

```kotlin
/** A titled sideways row of cards with "See all", like a store shelf. */
@Composable
private fun ShelfRow(s: AppState, row: HomeRow, onSeeAll: () -> Unit, onOpen: (Game) -> Unit) {
    Column(Modifier.padding(top = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onSeeAll, Modifier.focusRing(RoundedCornerShape(Radius.pill))) { Text("See all") }
        }
        // Vertical padding leaves room for the cards' glow and focus scale, which a lazy row would clip.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = Space.md)) {
            items(row.games, key = { it.release }) { g -> GameCard(s, g, Modifier.width(220.dp), selected = false) { onOpen(g) } }
        }
    }
}

/** The one big banner on the home: always a real wide thumbnail, never a stretched icon. */
@Composable
private fun FeaturedHero(s: AppState, g: Game, onOpen: (Game) -> Unit) {
    val shape = RoundedCornerShape(Radius.card)
    Box(Modifier.fillMaxWidth().height(260.dp).clip(shape).focusRing(shape).clickable { onOpen(g) }) {
        GameArt(s, g, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .85f), .6f to Color.Transparent)))
        Column(Modifier.align(Alignment.CenterStart).padding(Space.xxxl).widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("FEATURED · ${g.source.uppercase()}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(g.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
            Text("${g.sizeLabel} · updated ${g.updated.take(10)}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .75f))
            Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                Button({ s.install(g) }, Modifier.height(Size.control).focusRing(RoundedCornerShape(Radius.pill))) { Text("Install") }
                OutlinedButton({ onOpen(g) }, Modifier.height(Size.control).focusRing(RoundedCornerShape(Radius.pill))) { Text("Details", color = Color.White) }
            }
        }
    }
}
```

  Delete `FeaturedBanner`.

- [ ] **Step 6: Verify** — build, install; home shows rows "New & updated", "VR from SideQuest", "Open-source picks" (no mirror on the emulator, so no hero/Popular/Top rated — expected) and "Browse all" below; See all → grid with preset and Back returns home; no F-Droid title in any row.

- [ ] **Step 7: Commit** — `git commit -am "Store home: featured hero and shelf rows above Browse all"`

---

### Task 5: Browse all — Recommended sort, source dropdown, toggle chips

**Files:**
- Modify: `StoreLogic.kt` (`recommended`)
- Modify: `Library.kt` (`Sort.Recommended`, `Filter` without `All`, filter row)
- Test: `StoreLogicTest.kt`

**Interfaces:**
- Produces: `fun recommended(games: List<Game>): List<Game>`

- [ ] **Step 1: Failing test**

```kotlin
    @Test
    fun recommendedPutsFDroidLastNewestFirst() {
        val games = listOf(g("Fd", "F-Droid", "2026-12-01"), g("Old", "Mirror", "2025-01-01"), g("New", "SideQuest", "2026-06-01"))
        assertEquals(listOf("New", "Old", "Fd"), recommended(games).map { it.name })
    }
```

- [ ] **Step 2: Run** — fails.

- [ ] **Step 3: Implement** — `fun recommended(games: List<Game>) = games.sortedWith(compareBy<Game> { it.source == FDroid.name }.thenByDescending { it.updated })`.

- [ ] **Step 4: Run tests** — pass.

- [ ] **Step 5: Filter row** (`Library.kt`)
  - `enum class Sort(...) { Recommended("Recommended"), Name(...), … }`; `var sort by rememberSaveable { mutableStateOf(Sort.Recommended) }`; `Sort.Recommended -> recommended(l)` in the `when`.
  - `private enum class Filter(val label: String) { Installed("Installed"), Updates("Updates"), Favourites("Favourites") }`; `var filter by rememberSaveable { mutableStateOf<Filter?>(null) }`; filter `when (filter) { null -> true; … }`.
  - Row (only when `!home`): `TextButton("← Store")` (sets the same reset as the BackHandler); `SortMenu(sort, Sort.entries, { it.label }) { sort = it }`; `SortMenu(source, listOf("All") + sources, { if (it == "All") "All sources" else it }) { source = it }`; `Filter.entries` as `QChip(selected = filter == f, onClick = { filter = if (filter == f) null else f }, …)`; Select button (moved from header) and Install (n) when picking. Remove the source chips and the Hide 18+ chip.

- [ ] **Step 6: Verify** — build, install; Browse all via "See all": first cards are not F-Droid; Source dropdown lists sources; chips toggle off; ← Store returns home.

- [ ] **Step 7: Commit** — `git commit -am "Browse all: Recommended sort, source dropdown, toggle chips"`

---

### Task 6: Full-screen game page

**Files:**
- Modify: `Library.kt` (`GameDetail` rewritten; callers pass `onOpen`)

**Interfaces:**
- Consumes: `GameArt(s, g, modifier)`, `Details`, `ExpandableText`, `Screenshots`, `ScreenshotViewer`, `Trailer`, `QueueProgress`
- Produces: `GameDetail(s: AppState, g: Game, onOpen: (Game) -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: Rewrite `GameDetail`** as a full-screen dialog (`DialogProperties(usePlatformDefaultWidth = false)`, `Scaled(s)`, `Surface(Modifier.fillMaxSize(), color = colors.background)`), a vertically scrolling `Column` with:
  1. Hero `Box(Modifier.fillMaxWidth().height(340.dp))`: `GameArt(s, g, Modifier.matchParentSize())` (or `Trailer` in a centred 16:9 box when playing), bottom gradient to `colors.background`, top-left `FilledTonalIconButton(onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }`, bottom-right Trailer button.
  2. Title row (padding horizontal 32 dp): 64 dp icon (`rememberArt(null, g.iconUrl)` or `info?.cover`) in a rounded square; name (`headlineSmall` bold) and `publisher.ifEmpty { g.source }` + genres; spacer; primary button (`Install`/`Update`/`Reinstall`, or `Cancel` with `QueueProgress` under it when a job runs), `Launch` when installed, heart `IconToggleButton` for favourite (`Icons.Default.Favorite` / `Icons.Default.FavoriteBorder`). Primary button has the `FocusRequester`.
  3. Facts row: `★ 4.5 (120)` · size · `Version x` · `Updated <relative>` (`DateUtils.getRelativeTimeSpanString` from `yyyy-MM-dd`) · source — `bodyMedium`, `onSurfaceVariant`, separated by `·`.
  4. About (summary + `ExpandableText`), `Screenshots`, What's new — existing composables.
  5. "More like this": `latestPerTitle(s.games).filter { it.source == g.source && it.pkg != g.pkg }.sortedByDescending { it.updated }.take(10)` in a `LazyRow` of `GameCard(Modifier.width(200.dp))` → `onOpen(it)`.
  6. Technical: `Package ${g.pkg} · ${g.release}` in `labelSmall`.
- [ ] **Step 2: Callers** — `open?.let { GameDetail(s, it, onOpen = { next -> open = next }) { open = null } }`.
- [ ] **Step 3: Verify** — build, install; open an F-Droid title (feature graphic hero), a SideQuest title (icon tile hero), a GitHub title (What's new); Back closes; B (`adb shell input keyevent KEYCODE_BUTTON_B`) closes; More like this opens another page.
- [ ] **Step 4: Commit** — `git commit -am "Full-screen game page"`

---

## Self-review

- Spec §1 → Tasks 1–2; §2 → Task 4; §3 → Task 5; §4 → Task 3; §5 → Task 6; §6 → Task 1; Testing → tests in Tasks 1, 3, 4, 5 plus per-task emulator checks.
- Deviation (recorded in spec): the busy label sits at the top of the content area rather than under each screen's header (one place instead of six).
- Names used across tasks: `friendlyError`, `isGoodThumbnail`, `latestPerTitle`, `homeRows`, `RowKind`, `HomeRow`, `recommended`, `GameArt(s, g, modifier)`, `focusScale`, `SourcesDialog` (internal), `MyGamesScreen`, `Section.Store/MyGames/Downloads/Settings`.
