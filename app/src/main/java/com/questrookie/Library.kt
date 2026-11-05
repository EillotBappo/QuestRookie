package com.questrookie

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class Sort(val label: String) { Recommended("Recommended"), Name("Name"), Popular("Popular"), Rating("Top rated"), Updated("Recently updated"), Size("Largest"), LastPlayed("Last played") }
private enum class Filter(val label: String) { Installed("Installed"), Updates("Updates"), Favourites("Favourites") }

val Game.sizeLabel get() = sizeMb.toDoubleOrNull()?.let { formatSize((it * 1_048_576).toLong()) } ?: "$sizeMb MB"

// Tighter than the 24dp default so the Library title and every action fit on one line.
private val HeaderPad = PaddingValues(horizontal = 14.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(s: AppState) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.Recommended) }
    // null = no filter; the chips toggle, so there's no "All" chip.
    var filter by rememberSaveable { mutableStateOf<Filter?>(null) }
    var source by rememberSaveable { mutableStateOf("All") }
    var open by remember { mutableStateOf<Game?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    fun toggle(g: Game) { if (g.release in picked) picked.remove(g.release) else picked.add(g.release) }
    val games = s.games
    val versions = s.versions
    val sources = remember(games) { games.map { it.source }.distinct() }
    val shown = remember(games, query, sort, filter, source, versions, s.lastPlayed, s.favourites, s.hideAdult) {
        // Browsing shows the newest release per title; searching shows them all.
        val pool = if (query.isNotEmpty()) games else latestPerTitle(games)
        pool.filter { !(s.hideAdult && it.isAdult) }
            .filter { it.name.contains(query, true) || it.pkg.contains(query, true) }
            .filter { source == "All" || it.source == source }
            .filter {
                when (filter) {
                    null -> true
                    Filter.Installed -> s.stateOf(it) != GameState.NotInstalled
                    Filter.Updates -> s.stateOf(it) == GameState.Update
                    Filter.Favourites -> it.pkg in s.favourites
                }
            }
            .let { l ->
                when (sort) {
                    Sort.Recommended -> recommended(l)
                    Sort.Name -> l.sortedBy { it.name.lowercase() }
                    Sort.Popular -> l.sortedByDescending { it.popularity }
                    Sort.Rating -> l.sortedWith(compareByDescending<Game> { it.rating }.thenByDescending { it.ratingCount })
                    Sort.Updated -> l.sortedByDescending { it.updated }
                    Sort.Size -> l.sortedByDescending { it.sizeMb.toDoubleOrNull() ?: 0.0 }
                    Sort.LastPlayed -> l.sortedByDescending { s.lastPlayed[s.installedPkg(it)] ?: 0L }
                }
            }
    }
    val updates = remember(games, versions) { games.count { s.stateOf(it) == GameState.Update } }
    // The store home shows rows above the grid; any search, filter or "See all" switches to the plain grid, and Back returns.
    val home = !browsing && query.isEmpty() && filter == null && source == "All" && !picking
    fun goHome() { browsing = false; query = ""; filter = null; source = "All"; sort = Sort.Recommended; picking = false; picked.clear() }
    BackHandler(enabled = !home) { goHome() }
    val rows = remember(games, versions, s.favourites, s.hideAdult) { homeRows(games, s::stateOf, s.favourites, s.hideAdult, s.rookie.label.takeIf { s.rookie.mirrors.isNotEmpty() }) }
    fun seeAll(kind: RowKind) {
        browsing = true
        when (kind) {
            RowKind.Updates -> filter = Filter.Updates
            // Recommended is the row's own order (newest first), so See all continues the row.
            RowKind.New -> sort = Sort.Recommended
            RowKind.Popular -> sort = Sort.Popular
            RowKind.TopRated -> sort = Sort.Rating
            RowKind.VRSrc -> source = s.rookie.label
            RowKind.Favourites -> filter = Filter.Favourites
        }
    }
    // Featured games need a real wide thumbnail, so candidates' image sizes are read (bounds only) off the main thread.
    val heroes by produceState(emptyList<Game>(), games, versions) {
        value = withContext(Dispatchers.IO) {
            latestPerTitle(games).filter { s.stateOf(it) == GameState.NotInstalled }
                .sortedByDescending { it.updated }.take(30)
                .filter { g ->
                    val f = s.thumbnail(g) ?: return@filter false
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    isGoodThumbnail(o.outWidth, o.outHeight)
                }.take(5)
        }
    }
    // Controllers start on the first game, not the search box, which would open the keyboard.
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(games.isNotEmpty()) { runCatching { firstCard.requestFocus() } }
    // Newest of the mirror list and the SideQuest cache; 0 (never) when neither exists.
    val listTime = remember(games) { maxOf(s.rookie.metaDir.lastModified(), java.io.File(s.rookie.root, "sidequest").lastModified()) }
    val listAge = if (listTime == 0L) "" else " · list updated " + DateUtils.getRelativeTimeSpanString(listTime).toString().lowercase()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Store", if (games.isEmpty()) "" else count(shown.size, "title") + listAge) {
            if (games.isNotEmpty()) SearchField(query, { query = it }, "Search %,d games and apps".format(games.size), Modifier.weight(1f).widthIn(max = 460.dp))
            if (updates > 0) Button({ s.updateAll() }, Modifier.focusRing(RoundedCornerShape(24.dp)), contentPadding = HeaderPad, enabled = s.busy == null) { Text("Update all ($updates)") }
            FilledTonalIconButton({ refresh(s) }, Modifier.focusRing(CircleShape), enabled = s.busy == null) { Icon(Icons.Default.Refresh, "Refresh") }
            // Occasional actions live behind one button, so the header shows what matters at a glance.
            Box {
                var more by remember { mutableStateOf(false) }
                FilledTonalIconButton({ more = true }, Modifier.focusRing(CircleShape)) { Icon(Icons.Default.MoreVert, "More") }
                var sourcesOpen by remember { mutableStateOf(false) }
                DropdownMenu(more, { more = false }) {
                    // StreamFlow's Surprise Me: a random title from whatever the filters show; the home picks among titles you don't have yet.
                    val pool = if (home) shown.filter { s.stateOf(it) == GameState.NotInstalled }.ifEmpty { shown } else shown
                    if (pool.isNotEmpty()) DropdownMenuItem({ Text("Surprise me") }, { more = false; open = pool.random() })
                    DropdownMenuItem({ Text("Sources") }, { more = false; sourcesOpen = true })
                }
                if (sourcesOpen) SourcesDialog(s) { sourcesOpen = false }
            }
        }
        // Skeleton cards while the built-in lists load from cache, so the page has its shape from the first frame.
        if (games.isEmpty() && s.loadingCatalogs) {
            LazyVerticalGrid(GridCells.Adaptive(220.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), userScrollEnabled = false) {
                items(12) { Shimmer(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(Radius.tile))) }
            }
            return@Column
        }
        if (games.isEmpty()) {
            EmptyState(Icons.Default.Home, "No games yet", "Download the game list to get started.") {
                Button({ refresh(s) }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = s.busy == null) { Text("Download game list") }
            }
            return@Column
        }
        // Filters tuck away while scrolling down the list and come back on any scroll up, so more games fit.
        val grid = rememberLazyGridState()
        // A new view (home, See all, another filter or search) starts at the top, not wherever the last one was scrolled to.
        LaunchedEffect(home, query, filter, source, sort) { grid.scrollToItem(0) }
        AnimatedVisibility(!grid.canScrollBackward || grid.lastScrolledBackward) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (home) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.filter { it.games.isNotEmpty() }.forEach { r ->
                    QChip(selected = false, onClick = { seeAll(r.kind) }, label = { Text(r.title) }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                }
            }
            if (!home) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(::goHome, Modifier.align(Alignment.CenterVertically).focusRing(RoundedCornerShape(Radius.pill))) { Text("← Store") }
                SortMenu(sort, Sort.entries, { it.label }) { sort = it }
                // Sources are a dropdown, not a chip each: the list grows with every source added.
                if (sources.size > 1) SortMenu(source, listOf("All") + sources, { if (it == "All") "All sources" else it }) { source = it }
                Filter.entries.forEach { f ->
                    QChip(
                        selected = filter == f, onClick = { filter = if (filter == f) null else f },
                        modifier = Modifier.align(Alignment.CenterVertically).focusRing(RoundedCornerShape(8.dp)),
                        label = { Text(if (f == Filter.Updates && updates > 0) "${f.label} ($updates)" else f.label) },
                    )
                }
                FilledTonalButton({ picking = !picking; picked.clear() }, Modifier.align(Alignment.CenterVertically).focusRing(RoundedCornerShape(24.dp)), contentPadding = HeaderPad) {
                    Text(if (picking) "Done selecting" else "Select")
                }
                if (picking && picked.isNotEmpty()) Button({
                    val chosen = picked.toList()
                    games.filter { it.release in chosen && s.stateOf(it) != GameState.Installed }.forEach(s::install)
                    picked.clear(); picking = false
                }, Modifier.align(Alignment.CenterVertically).focusRing(RoundedCornerShape(24.dp)), contentPadding = HeaderPad) { Text("Install (${picked.size})") }
            }
            }
        }
        if (shown.isEmpty()) EmptyState(Icons.Default.Search, "No matches", "Try a different search or filter.")
        else LazyVerticalGrid(
            GridCells.Adaptive(220.dp), Modifier.weight(1f), state = grid, contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (home) {
                // Controllers start on the hero, else the first row's first card.
                if (heroes.isNotEmpty()) item(key = "hero", span = { GridItemSpan(maxLineSpan) }) { HeroCarousel(s, heroes, Modifier.focusRequester(firstCard)) { open = it } }
                rows.forEachIndexed { i, r ->
                    item(key = "row-${r.kind}", span = { GridItemSpan(maxLineSpan) }) {
                        ShelfRow(s, r, if (i == 0 && heroes.isEmpty()) Modifier.focusRequester(firstCard) else Modifier, { seeAll(r.kind) }) { open = it }
                    }
                }
                item(key = "browse-header", span = { GridItemSpan(maxLineSpan) }) {
                    Text("Browse all", Modifier.padding(top = Space.md), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            itemsIndexed(shown, key = { _, g -> g.release }) { i, g ->
                GameCard(s, g, (if (i == 0 && !home) Modifier.focusRequester(firstCard) else Modifier).staggered(i, g.release), selected = g.release in picked) {
                    if (picking) toggle(g) else open = g
                }
            }
        }
    }
    open?.let { GameDetail(s, it, onOpen = { next -> open = next }) { open = null } }
}

/** A titled sideways row of cards with "See all", like a store shelf. [firstCard] lands on the row's first card. */
@Composable
private fun ShelfRow(s: AppState, row: HomeRow, firstCard: Modifier, onSeeAll: () -> Unit, onOpen: (Game) -> Unit) {
    Column(Modifier.padding(top = Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (row.games.isNotEmpty()) TextButton(onSeeAll, Modifier.focusRing(RoundedCornerShape(Radius.pill))) { Text("See all") }
        }
        // Only the VRSrc row is ever empty: its list didn't load.
        if (row.games.isEmpty()) Row(Modifier.padding(vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
            val error = s.vrsrcError
            Text("Failed to load" + (error?.let { ": " + friendlyError(it) } ?: ""), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The full error, URL included, for pasting into a bug report or chat.
            if (error != null) {
                val clipboard = LocalClipboardManager.current

                // Existing Copy Error button
                TextButton({
                    clipboard.setText(AnnotatedString(error.toString()))
                    s.status = "Error copied"
                }, Modifier.focusRing(RoundedCornerShape(Radius.pill))) {
                    Text("Copy error")
                }

                Spacer(Modifier.width(Space.sm)) // Visual separation

                // NEW: Targeted Reload Button
                TextButton({ s.refreshVrsrc() }, Modifier.focusRing(RoundedCornerShape(Radius.pill))) {
                    Text("Try again", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        // Vertical padding leaves room for the cards' glow and focus scale, which a lazy row would clip.
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = Space.md)) {
            rowItemsIndexed(row.games, key = { _, g -> g.release }) { i, g ->
                GameCard(s, g, (if (i == 0) firstCard else Modifier).width(220.dp), selected = false) { onOpen(g) }
            }
        }
    }
}

/** Featured games, one big banner at a time, turning every 6 s; [modifier] (the first-focus target) goes on the page showing. */
@Composable
private fun HeroCarousel(s: AppState, games: List<Game>, modifier: Modifier, onOpen: (Game) -> Unit) {
    val pager = rememberPagerState { games.size }
    LaunchedEffect(pager, games.size) {
        while (games.size > 1) {
            kotlinx.coroutines.delay(6_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % games.size)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        HorizontalPager(pager, pageSpacing = 16.dp) { i -> FeaturedHero(s, games[i], if (i == pager.currentPage) modifier else Modifier, onOpen) }
        if (games.size > 1) Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(games.size) { i ->
                val on = i == pager.currentPage
                Box(Modifier.size(if (on) 10.dp else 6.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }
}

/** One featured banner: always a real wide thumbnail, never a stretched icon. */
@Composable
private fun FeaturedHero(s: AppState, g: Game, modifier: Modifier, onOpen: (Game) -> Unit) {
    val shape = RoundedCornerShape(Radius.card)
    Box(modifier.fillMaxWidth().height(260.dp).clip(shape).focusRing(shape).clickable { onOpen(g) }) {
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

/** Built-in catalogs always refresh; each configured source refreshes on its own, and one failing keeps its last list without hiding the rest. */
private fun refresh(s: AppState) = s.task("Downloading game list") {
    val catalogs = s.catalogs.flatMap { s.busy = "Downloading ${it.name} list"; it.refresh(s.rookie.root) }
    val failed = mutableListOf<String>()
    val fromSources = (listOf(s.rookie) + s.extras).filter { it.mirrors.isNotEmpty() }.flatMap { src ->
        runCatching {
            src.refreshMeta { stage, done, total ->
                s.busy = "${src.label}: " + if (total > 0) "$stage · ${formatSize(done)} of ${formatSize(total)}" else stage
            }.map { it.copy(source = src.label) }.also { if (src === s.rookie) s.vrsrcError = null }
        }.getOrElse { if (src === s.rookie) s.vrsrcError = it; failed += "${src.label} unavailable: ${friendlyError(it).replaceFirstChar(Char::lowercase)}. Showing your saved list"; src.games() }
    }
    s.games = fromSources + catalogs
    count(s.games.size, "title") + failed.joinToString("") { " · $it" }
}

/** Lists extra sources and adds new ones, e.g. a vrhub-server at http://<pi-ip>:39457/config.json. */
@Composable
internal fun SourcesDialog(s: AppState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sources") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mirror" + if (s.rookie.mirrors.isEmpty()) " (not set up)" else " · ${s.rookie.baseUri}", style = MaterialTheme.typography.bodyMedium)
                CATALOGS.forEach { Text("${it.name} · built in", style = MaterialTheme.typography.bodyMedium) }
                (s.catalogs.filterIsInstance<RepoCatalog>().map { it.name to it.url } + s.extras.map { it.label to it.baseUri }).forEach { (label, where) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$label · $where", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        TextButton({ s.removeSource(label) }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Add a source", style = MaterialTheme.typography.titleSmall)
                // Up here, not under the fields: below them it scrolls out of sight on a short window.
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name, e.g. Pi") })
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Config URL or JSON, or a repo's index-v1.json link") }, placeholder = { Text("http://192.168.1.50:39457/config.json") })
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("API key (optional)") })
            }
        },
        confirmButton = {
            Button({
                error = ""
                s.task("Adding $name") {
                    // A failure stays in the dialog; a snackbar would sit behind it, out of sight.
                    val added = try { s.addSource(name.trim(), input, key) } catch (e: Exception) { error = "Couldn't add $name: " + friendlyError(e).replaceFirstChar(Char::lowercase); return@task "" }
                    onClose()
                    added
                }
            }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = name.isNotBlank() && input.isNotBlank() && s.busy == null) { Text("Add") }
        },
        dismissButton = { TextButton(onClose, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Close") } },
    )
}

@Composable
fun <T> SortMenu(sort: T, options: List<T>, label: (T) -> String, onSort: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.focusRing(RoundedCornerShape(24.dp)), contentPadding = HeaderPad) { Text(label(sort)); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(label(o)) }, onClick = { onSort(o); open = false },
                    trailingIcon = { if (o == sort) Icon(Icons.Default.Check, null) },
                )
            }
        }
    }
}

@Composable
fun StatePill(st: GameState) {
    when (st) {
        GameState.Installed -> Pill("Installed", Green, Color(0xFF062015))
        GameState.Update -> Pill("Update", Orange, Color(0xFF221500))
        GameState.NotInstalled -> {}
    }
}

@Composable
private fun GameCard(s: AppState, g: Game, modifier: Modifier, selected: Boolean, onClick: () -> Unit) {
    val job = s.queue.active(g.release)
    // StreamFlow's image glow: the card casts a soft shadow in its art's colour.
    val art = rememberArt(s.thumbnail(g), g.thumbUrl.ifEmpty { g.iconUrl })
    val glow = remember(art) { art?.let(::glowColor) ?: Color.Black }
    val shape = RoundedCornerShape(Radius.tile)
    Card(
        onClick, modifier.focusScale().shadow(14.dp, shape, ambientColor = glow, spotColor = glow).focusRing(shape).glass(shape),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f)) {
            GameArt(s, g, Modifier.matchParentSize(), iconBias = -.6f)
            // Launcher style: the art is the tile; name and details sit on a fade at its foot.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))))
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(g.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(g.source, g.sizeLabel, if (g.rating > 0) "★ %.1f".format(g.rating) else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .75f), maxLines = 1,
                )
            }
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected) Pill("Selected", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                StatePill(s.stateOf(g))
                if (job != null) Pill(if (job.state == QState.Waiting) "Queued" else job.stage, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (job != null) QueueProgress(job, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

/** A game's store page, full screen over the Store. Back (or B) closes it; [onOpen] swaps in another game from "More like this". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameDetail(s: AppState, g: Game, onOpen: (Game) -> Unit, onDismiss: () -> Unit) {
    var trailer by remember(g) { mutableStateOf(false) }
    // Store text and screenshots load off the main thread; OculusDB may need the network the first time.
    val info by produceState<Details?>(null, g) { value = withContext(Dispatchers.IO) { runCatching { s.details(g) }.getOrNull() } }
    var viewing by remember(g) { mutableStateOf<Int?>(null) }
    val st = s.stateOf(g)
    val job = s.queue.active(g.release)
    val colors = MaterialTheme.colorScheme
    // Mirror games have no icon of their own; OculusDB's square store cover stands in.
    val icon = g.iconUrl.ifEmpty { info?.cover.orEmpty() }
    val more = remember(g, s.games) { latestPerTitle(s.games).filter { it.source == g.source && it.pkg != g.pkg }.sortedByDescending { it.updated }.take(10) }
    // Controllers land on the primary action when the page opens.
    val primary = remember { FocusRequester() }
    LaunchedEffect(g) { runCatching { primary.requestFocus() } }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = colors.background) { key(g) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.xxxl)) {
                Box(Modifier.fillMaxWidth().height(340.dp)) {
                    if (trailer) Box(Modifier.matchParentSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Trailer(g.name, Modifier.fillMaxHeight().aspectRatio(16 / 9f))
                    } else {
                        GameArt(s.thumbnail(g), g.name, Modifier.matchParentSize(), url = g.thumbUrl, icon = icon)
                        // Fades into the page so the title block reads as part of the art.
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, .5f to Color.Transparent, 1f to colors.background)))
                        Button(
                            { trailer = true }, Modifier.align(Alignment.BottomEnd).padding(Space.xxxl).height(Size.control).focusRing(RoundedCornerShape(Radius.pill)),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = .6f), contentColor = Color.White),
                        ) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs)); Text("Trailer") }
                    }
                    FilledTonalIconButton(onDismiss, Modifier.align(Alignment.TopStart).padding(Space.lg).focusRing(CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
                Column(Modifier.padding(horizontal = Space.xxxl), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                        rememberArt(null, icon)?.let {
                            Image(it, null, Modifier.size(72.dp).clip(RoundedCornerShape(22)).background(colors.surfaceContainerHighest), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                Text(g.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                StatePill(st)
                            }
                            val byline = listOf(info?.publisher.orEmpty().ifEmpty { g.source }) + info?.genres.orEmpty().take(3)
                            Text(byline.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        if (job != null) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            OutlinedButton({ s.queue.cancel(job) }, Modifier.height(Size.control).focusRequester(primary).focusRing(RoundedCornerShape(Radius.pill))) { Text("Cancel") }
                            Text(listOf(job.stage, job.detail).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            QueueProgress(job, Modifier.width(180.dp))
                        } else Button({ s.install(g) }, Modifier.height(Size.control).focusRequester(primary).focusRing(RoundedCornerShape(Radius.pill))) {
                            Text(when (st) { GameState.NotInstalled -> "Install"; GameState.Update -> "Update"; GameState.Installed -> "Reinstall" })
                        }
                        if (st != GameState.NotInstalled) FilledTonalButton({ s.launch(g.pkg) }, Modifier.height(Size.control).focusRing(RoundedCornerShape(Radius.pill))) { Text("Launch") }
                        val fav = g.pkg in s.favourites
                        IconToggleButton(fav, { s.toggleFavourite(g.pkg) }, Modifier.focusRing(CircleShape)) {
                            Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (fav) "Unfavourite" else "Favourite", tint = if (fav) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                    Text(
                        listOfNotNull(
                            if (g.rating > 0) "★ %.1f".format(g.rating) + if (g.ratingCount > 0) " (${g.ratingCount})" else "" else null,
                            g.sizeLabel, "Version ${g.version}", updatedAgo(g.updated, java.time.LocalDate.now()),
                            if (st == GameState.Update) "You have ${s.versions[s.installedPkg(g)]}" else null,
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    )
                    info?.let { d ->
                        if (d.summary.isNotEmpty() || d.description.isNotEmpty()) PageSection("About") {
                            if (d.summary.isNotEmpty() && d.summary != d.description) Text(d.summary, style = MaterialTheme.typography.bodyLarge)
                            if (d.description.isNotEmpty()) ExpandableText(richText(d.description))
                        }
                        if (d.screenshots.isNotEmpty()) PageSection("Screenshots", wide = true) { Screenshots(d.screenshots) { viewing = it } }
                        if (d.whatsNew.isNotEmpty()) PageSection("What's new") { ExpandableText(AnnotatedString(d.whatsNew)) }
                    }
                    if (more.isNotEmpty()) PageSection("More from ${g.source}", wide = true) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = Space.md)) {
                            rowItemsIndexed(more, key = { _, m -> m.release }) { _, m -> GameCard(s, m, Modifier.width(200.dp), selected = false) { onOpen(m) } }
                        }
                    }
                    Text("Package ${g.pkg.removePrefix("github:")} · ${g.release}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        } }
    }
    val shots = info?.screenshots.orEmpty()
    viewing?.let { start -> ScreenshotViewer(s, shots, start) { viewing = null } }
}

/** A heading and its content, the shape every block on a game page shares. Text stays readable-width; [wide] rows of cards use it all. */
@Composable
private fun PageSection(title: String, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column((if (wide) Modifier.fillMaxWidth() else Modifier.widthIn(max = 960.dp)).padding(top = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

/** F-Droid descriptions are HTML; other sources send plain text with line breaks that HTML parsing would collapse. */
private fun richText(t: String) = if (Regex("<[a-zA-Z/][^>]*>").containsMatchIn(t)) AnnotatedString.fromHtml(t.trim()) else AnnotatedString(t)

/** Four lines, with More/Less when the text runs longer. */
@Composable
private fun ExpandableText(text: AnnotatedString) {
    var more by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Text(
        text, Modifier.clickable { more = !more }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, onTextLayout = { if (!more) overflows = it.hasVisualOverflow },
    )
    if (overflows || more) TextButton({ more = !more }, Modifier.focusRing(RoundedCornerShape(Radius.pill))) { Text(if (more) "Less" else "More") }
}

/** A sideways strip at a fixed height, each shot at its own shape (phone screenshots are tall, VR ones wide). */
@Composable
private fun Screenshots(urls: List<String>, onOpen: (Int) -> Unit) {
    // A plain scrolling Row: a game has a handful of screenshots, not enough to need a lazy list.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        urls.forEachIndexed { i, url ->
            val img = rememberArt(null, url)
            val shape = RoundedCornerShape(Radius.chip)
            val m = Modifier.height(180.dp).clip(shape).focusRing(shape).clickable { onOpen(i) }
            if (img == null) Shimmer(m.aspectRatio(9 / 16f))
            else Image(img, "Screenshot ${i + 1}", m.aspectRatio(img.width.toFloat() / img.height), contentScale = ContentScale.Crop)
        }
    }
}

/** Full-screen screenshots; swipe or use the D-pad between them, Back closes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenshotViewer(s: AppState, urls: List<String>, start: Int, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(start) { urls.size }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .92f))) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                val img = rememberArt(null, urls[i], maxWidth = 1600)
                // Bottom gap keeps the page counter off the image.
                Box(Modifier.fillMaxSize().padding(start = Space.xxl, top = Space.xxl, end = Space.xxl, bottom = 56.dp), contentAlignment = Alignment.Center) {
                    if (img == null) CircularProgressIndicator()
                    else Image(img, "Screenshot ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            Text("${pager.currentPage + 1} / ${urls.size}", Modifier.align(Alignment.BottomCenter).padding(Space.lg), color = Color.White)
            IconButton(onClose, Modifier.align(Alignment.TopEnd).padding(Space.lg).focusRing(CircleShape)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
        }
    }
}

@Composable
private fun Trailer(name: String, modifier: Modifier) {
    // "" = still searching, null = nothing found
    val id by produceState<String?>("", name) { value = withContext(Dispatchers.IO) { runCatching { youtubeId("$name VR trailer") }.getOrNull() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val v = id) {
            "" -> CircularProgressIndicator()
            null -> Text("No trailer found", color = Color.White)
            else -> AndroidView(
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        setBackgroundColor(android.graphics.Color.BLACK)
                        // YouTube refuses embeds without a referrer, so load from an https base URL instead of the raw embed URL.
                        loadDataWithBaseURL("https://questrookie.app/", embedHtml(v), "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.matchParentSize(),
                onRelease = { it.destroy() },
            )
        }
    }
}

private fun embedHtml(id: String) = """<html><body style="margin:0;background:#000">
<iframe style="position:fixed;inset:0;width:100%;height:100%;border:0" src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0"
 allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe></body></html>"""

/** First hit of a YouTube search. */
// ponytail: scrapes the results page (no API key); swap to the YouTube Data API if the markup changes.
private fun youtubeId(query: String): String? {
    val c = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
    c.setRequestProperty("Accept-Language", "en-US")
    c.setRequestProperty("Cookie", "CONSENT=YES+1")
    val html = c.inputStream.bufferedReader().use { it.readText() }
    return Regex("\"videoId\":\"([\\w-]{11})\"").find(html)?.groupValues?.get(1)
}
