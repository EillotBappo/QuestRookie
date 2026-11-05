package com.questrookie

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val Orange = Color(0xFFFFAA28)
val Green = Color(0xFF5BD6A0)
private val Ink = Color(0xFF1A1000)
// Deep indigo page with soft colour shapes (a launcher wallpaper) under frosted-glass panels, with QuestRookie's orange accent.
val CardColor = Color(0x17FFFFFF)
val RailColor = Color(0x26000000)
private val Night = Color(0xFF151935)

/** One spacing scale, one radius scale and one control height for every screen, so nothing is sized by eye. */
object Space { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 20.dp; val xxl = 24.dp; val xxxl = 32.dp }
object Radius { val chip = 12.dp; val control = 14.dp; val tile = 16.dp; val card = 20.dp; val sheet = 28.dp; val pill = 100.dp }
object Size {
    /** Search bars, chips and buttons share this height, so a row of mixed controls lines up. */
    val control = 40.dp
    /** Filter chips sit a step smaller than other controls. */
    val chip = 32.dp
}

private val Scheme = darkColorScheme(
    primary = Orange, onPrimary = Ink,
    primaryContainer = Color(0xFF3A2A10), onPrimaryContainer = Color(0xFFFFD9A0),
    secondary = Color(0xFF9AA4FF), onSecondary = Color(0xFF10132B),
    // Translucent white: it paints every tonal button, so they read as glass on the indigo page; orange is the only accent.
    secondaryContainer = Color(0x24FFFFFF), onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Green,
    background = Night, onBackground = Color(0xFFFFFFFF),
    surface = Night, onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2B3160), onSurfaceVariant = Color(0xFFA9B1D9),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Night, surfaceContainerLow = Color(0xFF1B2045),
    surfaceContainer = Color(0xFF1F244D), surfaceContainerHigh = Color(0xFF272D5C),
    surfaceContainerHighest = Color(0xFF323A70),
    outline = Color(0x33FFFFFF), outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFFF6B6B),
)

@Composable
fun QuestTheme(content: @Composable () -> Unit) {
    val type = Typography()
    MaterialTheme(
        colorScheme = Scheme,
        typography = type.copy(
            headlineMedium = type.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.3).sp),
            titleLarge = type.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = type.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(Radius.control), small = RoundedCornerShape(Radius.chip), medium = RoundedCornerShape(Radius.tile), large = RoundedCornerShape(Radius.card)),
        content = content,
    )
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

private val images = LruCache<String, ImageBitmap>(96)

/** Decodes off the main thread and caches, so grids scroll without jank or flicker. */
@Composable
fun rememberImage(key: String?, load: () -> Bitmap?): ImageBitmap? =
    produceState(key?.let { images.get(it) }, key) {
        if (key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull()?.asImageBitmap()?.also { images.put(key, it) } }
    }.value

fun decodeSampled(file: File, maxWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

fun Drawable.toBitmap(size: Int): Bitmap =
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { setBounds(0, 0, size, size); draw(Canvas(it)) }

/**
 * A game's art: its local thumbnail, else [url] fetched once into the cache dir, so a refresh never downloads thousands of icons.
 * [maxWidth] caps the decoded size; full-screen viewers ask for more than grid cards.
 */
@Composable
fun rememberArt(file: File?, url: String, maxWidth: Int = 640): ImageBitmap? {
    val cacheDir = androidx.compose.ui.platform.LocalContext.current.cacheDir
    return rememberImage((file?.path ?: url.ifEmpty { null })?.let { if (maxWidth == 640) it else "$it@$maxWidth" }) {
        file?.let { decodeSampled(it, maxWidth) } ?: run {
            val cached = File(cacheDir, "art/" + url.hashCode().toUInt().toString(16))
            if (!cached.exists()) {
                cached.parentFile?.mkdirs()
                val tmp = File(cached.path + ".part")
                java.net.URL(url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(cached)
            }
            decodeSampled(cached, maxWidth)
        }
    }
}

/** StreamFlow's image glow: the art's average colour, brightened and saturated so it reads as light, not mud. */
fun glowColor(img: ImageBitmap): Color {
    val px = Bitmap.createScaledBitmap(img.asAndroidBitmap(), 1, 1, true).getPixel(0, 0)
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(px, it) }
    return Color.hsv(hsv[0], (hsv[1] * 1.4f).coerceAtMost(.9f), hsv[2].coerceAtLeast(.6f))
}

/** Fade + rise the first time each [id] appears; scrolling back to it never replays. Capped so deep items show at once. */
fun Modifier.staggered(index: Int, id: Any): Modifier = composed {
    val first = remember(id) { seen.add(id) }
    val p = remember(id) { androidx.compose.animation.core.Animatable(if (first && index < 12) 0f else 1f) }
    LaunchedEffect(id) {
        if (p.value < 1f) { kotlinx.coroutines.delay(index * 40L); p.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 300f)) }
    }
    graphicsLayer { alpha = p.value; translationY = (1 - p.value) * 18.dp.toPx(); scaleX = .97f + .03f * p.value; scaleY = scaleX }
}

// ponytail: grows for the session; fine for a few thousand titles.
private val seen = HashSet<Any>()

/** Skeleton block with a moving highlight band, shown while lists load. */
@Composable
fun Shimmer(modifier: Modifier) {
    val x by androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer").animateFloat(
        -1f, 2f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200)), label = "x",
    )
    val base = MaterialTheme.colorScheme.surfaceContainer
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.drawBehind {
        drawRect(Brush.linearGradient(listOf(base, light, base), start = Offset(size.width * x, 0f), end = Offset(size.width * (x + .6f), size.height)))
    })
}

/**
 * A title's art: a good wide thumbnail fills the area; otherwise its icon sits on a blurred copy of itself; otherwise a letter.
 * [iconBias] moves that icon up (-1) or down (1), so text laid over the art doesn't cover it.
 */
@Composable
fun GameArt(s: AppState, g: Game, modifier: Modifier = Modifier, iconBias: Float = 0f) = GameArt(s.thumbnail(g), g.name, modifier, url = g.thumbUrl, icon = g.iconUrl, iconBias = iconBias)

@Composable
fun GameArt(file: File?, title: String, modifier: Modifier = Modifier, url: String = "", icon: String = "", iconBias: Float = 0f) {
    val thumb = rememberArt(file, url)
    if (thumb != null && isGoodThumbnail(thumb.width, thumb.height)) {
        Image(thumb, null, modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentScale = ContentScale.Crop)
        return
    }
    // A thumbnail that isn't wide (e.g. a square cover) still beats no icon.
    IconTile(thumb ?: rememberArt(null, icon), title, modifier, iconBias)
}

/** An icon centred on a blurred, darkened copy of itself, so square art never gets cropped into a wide card. */
@Composable
private fun IconTile(img: ImageBitmap?, title: String, modifier: Modifier, iconBias: Float) {
    val colors = MaterialTheme.colorScheme
    // No art at all: a colour of its own per title, so a row of placeholders doesn't read as one grey block.
    val hue = remember(title) { (title.hashCode() and 0x7fffffff) % 360f }
    val bg = if (img != null) Modifier.background(colors.surfaceContainerHigh)
    else Modifier.background(Brush.linearGradient(listOf(Color.hsv(hue, .55f, .32f), Color.hsv((hue + 40) % 360, .55f, .14f))))
    Box(modifier.then(bg), contentAlignment = Alignment.Center) {
        if (img != null) {
            val soft = remember(img) { haze(img) }
            Image(soft, null, Modifier.matchParentSize().graphicsLayer { alpha = .6f }, contentScale = ContentScale.Crop)
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .1f), Color.Black.copy(alpha = .45f)))))
        val shape = RoundedCornerShape(22)
        Box(Modifier.align(androidx.compose.ui.BiasAlignment(0f, iconBias)).fillMaxHeight(.56f).aspectRatio(1f).shadow(10.dp, shape).clip(shape).background(if (img != null) colors.surfaceContainerHighest else Color.hsv(hue, .5f, .5f)), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else Text(title.trim().take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun AppIcon(key: String, size: Dp = 56.dp, load: () -> Drawable?) {
    val img = rememberImage("icon:$key") { load()?.toBitmap(144) }
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        img?.let { Image(it, null, Modifier.matchParentSize()) }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text, Modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
        color = content, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

@Composable
fun Logo(modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(Brush.linearGradient(listOf(Orange, Color(0xFFFF6A3D)))),
        contentAlignment = Alignment.Center,
    ) {
        // A play triangle inside a ring with a tail: a "Q" that reads as "play".
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize(.62f)) {
            val w = this.size.minDimension
            val stroke = w * .13f
            drawCircle(Ink, radius = w / 2 - stroke / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawLine(Ink, Offset(w * .68f, w * .68f), Offset(w * .98f, w * .98f), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * .41f, w * .32f); lineTo(w * .41f, w * .68f); lineTo(w * .7f, w * .5f); close() }, Ink)
        }
    }
}

/** The app state for widgets that sit on every page (the top bar's vitals); provided once by [App]. */
val LocalAppState = staticCompositionLocalOf<AppState?> { null }

/**
 * Every page's top bar, one thin row like a launcher's: logo, the page and its count, the page's own controls,
 * then the headset's vitals. One row instead of a title block leaves the height to the list below.
 */
@Composable
fun ScreenHeader(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(top = Space.md, bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Logo(size = 32.dp)
        Column(Modifier.widthIn(max = 240.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
        LocalAppState.current?.let { Vitals(it) }
    }
}

/** ADB, clock, battery and free space, right-aligned in the top bar. */
@Composable
private fun Vitals(s: AppState) {
    val ctx = s.ctx
    // Polled every 30 s: Android sends Compose no cheap change callback for the clock, battery or storage.
    fun read() = Triple(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date()), batteryPercent(ctx) to isCharging(ctx), s.freeSpace())
    val now by produceState(read()) { while (true) { kotlinx.coroutines.delay(30_000); value = read() } }
    val (time, battery, free) = now
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        VitalText(QIcons.Usb, if (s.installer.connected) "ADB" else "No ADB", if (s.installer.connected) Green else dim)
        Text("|", color = dim)
        VitalText(null, time, MaterialTheme.colorScheme.onSurface)
        battery.first?.let { pct ->
            Text("|", color = dim)
            // Red under 20%, orange under 50%; "+" while charging.
            VitalText(QIcons.Battery, "$pct%" + if (battery.second) "+" else "", when { pct < 20 -> MaterialTheme.colorScheme.error; pct < 50 -> Orange; else -> Green })
        }
        Text("|", color = dim)
        VitalText(null, "${formatSize(free)} free", MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun VitalText(icon: ImageVector?, label: String, tint: Color) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    icon?.let { Icon(it, null, Modifier.size(14.dp), tint = tint) }
    Text(label, color = tint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/** Selected filter chips fill orange, like the mockups. Kept apart from the theme because secondaryContainer also paints every tonal button. */
@Composable
fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
)

/** Headset storage as a labelled bar, for page headers. */
@Composable
fun StorageBar(s: AppState, modifier: Modifier = Modifier.width(320.dp)) {
    val total = s.totalSpace().coerceAtLeast(1)
    val used = total - s.freeSpace()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Storage", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatSize(used)} of ${formatSize(total)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            { used.toFloat() / total }, Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
            trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {}, gapSize = 0.dp,
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    // Scrolls so the action button stays reachable when the window is short (landscape with the nav bar).
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(body, Modifier.widthIn(max = 440.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

@Composable
fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(MaterialTheme.shapes.medium).background(CardColor).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = colors.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (action != null) TextButton(onAction, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text(action) }
    }
}

/** Compact search bar at [Size.control] height, so it lines up with the chips and buttons beside it (an OutlinedTextField is 56dp). */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radius.control)
    Row(
        modifier.height(Size.control).clip(shape).background(colors.surfaceVariant).focusRing(shape).padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
            )
        }
        if (value.isNotEmpty()) IconButton({ onChange("") }, Modifier.size(28.dp).focusRing(CircleShape)) { Icon(Icons.Default.Clear, "Clear", Modifier.size(16.dp)) }
    }
}

/** Filter chip at [Size.control] height, pill-shaped, selected = filled orange. Every filter row uses this one. */
@Composable
fun QChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null) {
    FilterChip(
        selected, onClick, label, modifier.height(Size.chip), enabled = enabled, leadingIcon = leadingIcon,
        shape = RoundedCornerShape(Radius.pill), colors = chipColors(),
        border = FilterChipDefaults.filterChipBorder(enabled, selected, borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = Color.Transparent),
    )
}

/** Outline shown while a D-pad or controller has focus on the element. Touch never shows it. */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(16.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier.border(3.dp, Orange, shape) else Modifier)
}

/**
 * A soft, blurred backdrop from [img]: shrunk to 16px, box-blurred three times (close to a gaussian), then grown to 64px.
 * Stretched behind an icon it reads as a heavy blur, at a tiny fraction of a blur effect's cost: a blur effect over a
 * full-width header stalled rendering long enough to freeze the app on the software-rendered emulator.
 */
private fun haze(img: ImageBitmap): ImageBitmap {
    val n = 16
    val px = IntArray(n * n).also { Bitmap.createScaledBitmap(img.asAndroidBitmap(), n, n, true).getPixels(it, 0, n, 0, 0, n, n) }
    repeat(3) {
        val out = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (dy in -1..1) for (dx in -1..1) {
                val p = px[(y + dy).coerceIn(0, n - 1) * n + (x + dx).coerceIn(0, n - 1)]
                a += p ushr 24; r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
            }
            out[y * n + x] = (a / 9 shl 24) or (r / 9 shl 16) or (g / 9 shl 8) or (b / 9)
        }
        out.copyInto(px)
    }
    return Bitmap.createScaledBitmap(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), 64, 64, true).asImageBitmap()
}

/** Grows a focused element slightly, so a controller user sees where they are beyond the ring. */
fun Modifier.focusScale(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.04f else 1f, label = "focusScale")
    onFocusChanged { focused = it.hasFocus }.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Card surface: frosted glass over the page's colour shapes, with a faint light edge so it holds its shape at panel distance. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(Radius.tile)): Modifier = this.clip(shape).background(CardColor).border(1.dp, Color(0x14FFFFFF), shape)

/** The page behind everything: deep indigo with a soft blue and a soft purple shape, like a launcher wallpaper. */
fun Modifier.backdrop(): Modifier = this.background(Brush.linearGradient(listOf(Color(0xFF1A1F4D), Night, Color(0xFF221A4A)))).drawBehind {
    fun glow(color: Color, x: Float, y: Float, r: Float) {
        val c = Offset(size.width * x, size.height * y)
        val radius = size.maxDimension * r
        drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = c, radius = radius), radius = radius, center = c)
    }
    glow(Color(0x553D6BE0), .12f, .08f, .5f)
    glow(Color(0x447B4FD6), .92f, .95f, .55f)
}

/** The few Material icons the core set lacks, from their published path data, rather than pulling in the whole extended icon library. */
object QIcons {
    private fun icon(name: String, path: String) = androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(androidx.compose.ui.graphics.vector.addPathNodes(path), fill = androidx.compose.ui.graphics.SolidColor(Color.Black)).build()
    val Gamepad = icon("Gamepad", "M21.58,16.09l-1.09,-7.66C20.21,6.46 18.52,5 16.53,5H7.47C5.48,5 3.79,6.46 3.51,8.43l-1.09,7.66C2.2,17.63 3.39,19 4.94,19c0.68,0 1.32,-0.27 1.8,-0.75L9,16h6l2.25,2.25c0.48,0.48 1.13,0.75 1.8,0.75C20.61,19 21.8,17.63 21.58,16.09zM11,11H9v2H8v-2H6v-1h2V8h1v2h2V11zM15,10c-0.55,0 -1,-0.45 -1,-1c0,-0.55 0.45,-1 1,-1s1,0.45 1,1C16,9.55 15.55,10 15,10zM17,13c-0.55,0 -1,-0.45 -1,-1c0,-0.55 0.45,-1 1,-1s1,0.45 1,1C18,12.55 17.55,13 17,13z")
    val Download = icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    val Apps = icon("Apps", "M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z")
    val Battery = icon("Battery", "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z")
    val Usb = icon("Usb", "M15,7v4h1v2h-3V5h2l-3,-4 -3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93 0,-1.21 -0.99,-2.2 -2.2,-2.2 -1.21,0 -2.2,0.99 -2.2,2.2 0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95 0,1.22 0.99,2.2 2.2,2.2 1.21,0 2.2,-0.98 2.2,-2.2 0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2h1V7h-4z")
}

/** A tuner-style picker: the current choice in a dark pill with ▾; tapping it lists [options] (label to value). */
@Composable
fun DropPill(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, enabled: Boolean = true, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.pill)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(Size.control).clip(shape).background(Color.Black.copy(alpha = .35f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .4f), shape).focusRing(shape)
                .clickable(enabled = enabled) { open = true }.padding(start = Space.lg, end = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.firstOrNull { it.second == selected }?.first ?: selected, Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (label, v) ->
                DropdownMenuItem({ Text(label) }, { open = false; onPick(v) }, leadingIcon = if (v == selected) {{ Icon(Icons.Default.Check, null) }} else null)
            }
        }
    }
}
