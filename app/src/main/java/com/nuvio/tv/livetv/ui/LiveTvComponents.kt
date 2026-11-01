package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram

import android.view.LayoutInflater
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import coil3.request.allowHardware
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** True = focused items are filled with the accent color; false = tinted fill with an accent outline. */
internal val LocalLiveSolidHighlight = staticCompositionLocalOf { false }

/** "Guide darkness" setting ("standard" = Lightest, "darker" = Standard, "solid" = Darkest): the guide bars follow it. */
internal val LocalGuideDarkness = staticCompositionLocalOf { "darker" }

private val TEXT_OVER_PICTURE = androidx.compose.ui.graphics.Shadow(
    color = Color.Black.copy(alpha = 0.55f),
    offset = androidx.compose.ui.geometry.Offset(0f, 1.5f),
    blurRadius = 4f
)

/**
 * @param marquee scrolls single-line text that doesn't fit, like TiviMate does for the
 * focused program. Only turn it on for the focused item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NuvioTheme.colors.TextPrimary,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign? = null,
    marquee: Boolean = false,
    /** A soft dark shadow, for text over the picture (the info panel). */
    shadow: Boolean = false
) {
    Text(
        style = if (shadow) androidx.compose.material3.LocalTextStyle.current.copy(shadow = TEXT_OVER_PICTURE) else androidx.compose.material3.LocalTextStyle.current,
        text = text,
        modifier = if (marquee && maxLines == 1) {
            modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 1_200,
                repeatDelayMillis = 1_500
            )
        } else modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        maxLines = maxLines,
        softWrap = !(marquee && maxLines == 1),
        overflow = if (marquee && maxLines == 1) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = align
    )
}

/**
 * A description that fits [maxLines] lines, or, when it's longer than that, scrolls up slowly
 * so the whole thing can be read: it waits on the first lines, scrolls at a slow reading pace,
 * waits on the last lines, then starts again from the top.
 */
@Composable
internal fun ScrollingDescription(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NuvioTheme.colors.TextSecondary,
    size: TextUnit = 14.sp,
    maxLines: Int = 2,
    weight: FontWeight = FontWeight.Normal
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lineHeight = size * 1.3f
    val boxHeight = with(density) { lineHeight.toDp() } * maxLines.coerceAtLeast(1)
    val scroll = androidx.compose.foundation.rememberScrollState()
    val max = scroll.maxValue
    LaunchedEffect(text, max, maxLines) {
        if (max <= 0) return@LaunchedEffect
        // About one line every 2.5 seconds (a slow reading pace), whatever the text size. It sits
        // on the first lines for a while first, and on the last lines for a while at the end.
        val pxPerSecond = with(density) { lineHeight.toPx() } / 2.5f
        while (true) {
            scroll.scrollTo(0)
            kotlinx.coroutines.delay(6_000)
            scroll.animateScrollTo(
                max,
                androidx.compose.animation.core.tween((max / pxPerSecond * 1000).toInt().coerceAtLeast(1_500), easing = androidx.compose.animation.core.LinearEasing)
            )
            kotlinx.coroutines.delay(6_000)
        }
    }
    Box(modifier.heightIn(max = boxHeight).clipToBounds()) {
        Text(
            text = text,
            color = color,
            fontSize = size,
            fontWeight = weight,
            lineHeight = lineHeight,
            modifier = Modifier.verticalScroll(scroll, enabled = false)
        )
    }
}

/**
 * The backdrop behind the whole guide / Sports hub (Settings → "Guide & Sports Background"). A
 * flat near-black background made the glass cards' translucency invisible — the fill and what's
 * behind it were nearly the same tone, so alpha blending had nothing to show against. Several soft
 * colour blobs of different hue, size and brightness, laid freely over a dark base (no single
 * centre), give the glass a lot to read against, and no two areas of the screen look alike.
 */
private class Blob(val x: Float, val y: Float, val r: Float, val color: Color, val a: Float)

/** The choices for the guide / Sports / On Demand background: the app icon colors, plus Onyx. */
internal object GuideBackdrops {
    val all: List<Pair<String, String>> = listOf(
        "auto" to "Match Theme",
        "onyx" to "Onyx",
        "original" to "Original",
        "arctic" to "Arctic Blue",
        "emerald" to "Emerald",
        "rose" to "Rose Gold",
        "copper" to "Copper",
        "graphite" to "Graphite"
    )

    /** Settings saved by earlier versions: Midnight Blue is now Arctic Blue, Dark Red is Rose Gold. */
    fun normalize(key: String): String = when {
        key.startsWith("auto:") -> key // already resolved to the theme's colors
        // "oled" was the default, so it follows the theme until someone picks Onyx themselves.
        key == "oled" -> "auto"
        key == "midnight" -> "arctic"
        key == "red" -> "rose"
        all.any { it.first == key } -> key
        else -> "auto"
    }

    fun label(key: String): String = all.firstOrNull { it.first == normalize(key) }?.second ?: "Match Theme"

    fun next(key: String): String {
        val i = all.indexOfFirst { it.first == normalize(key) }.coerceAtLeast(0)
        return all[(i + 1) % all.size].first
    }
}

/** The glow colors of each app icon (the banner's colors), two per choice. */
private fun accentOf(style: String): Pair<Color, Color> = when {
    style.startsWith("auto:") -> {
        val p = style.split(':')
        Color(p[1].toLong().toInt()) to Color(p[2].toLong().toInt())
    }
    else -> accentOfKey(style)
}

private fun accentOfKey(style: String): Pair<Color, Color> = when (style) {
    "original" -> Color(0xFF6A4FD0) to Color(0xFF3A2D7A)
    "arctic" -> Color(0xFF2B5FB0) to Color(0xFF3B3C9E)
    "emerald" -> Color(0xFF1FA58B) to Color(0xFF1B6C5F)
    "rose" -> Color(0xFFB05A9A) to Color(0xFF7A3A6E)
    "copper" -> Color(0xFFC8773A) to Color(0xFF8A4A2A)
    else -> Color(0xFF59606E) to Color(0xFF3E5566) // "graphite"
}

private fun backdropBase(style: String): Color = if (style.startsWith("auto:")) {
    androidx.compose.ui.graphics.lerp(accentOf(style).first, Color.Black, 0.94f)
} else when (GuideBackdrops.normalize(style)) {
    "onyx" -> Color(0xFF000000)
    "original" -> Color(0xFF0B0A18)
    "arctic" -> Color(0xFF050D1A)
    "emerald" -> Color(0xFF05130F)
    "rose" -> Color(0xFF140711)
    "copper" -> Color(0xFF140B07)
    else -> Color(0xFF0E0F11) // "graphite"
}

private fun backdropBlobs(styleKey: String): List<Blob> {
    val style = GuideBackdrops.normalize(styleKey)
    if (style == "onyx") return listOf(
        Blob(0.14f, 0.00f, 0.60f, Color(0xFF2A2F4A), 0.40f),
        Blob(0.92f, 0.82f, 0.55f, Color(0xFF3A2440), 0.30f),
        Blob(0.55f, 0.45f, 0.28f, Color(0xFF1E2A33), 0.20f)
    )
    val (a, b) = accentOf(style)
    return listOf(
        Blob(0.10f, 0.04f, 0.75f, a, 0.55f),
        Blob(0.94f, 0.34f, 0.58f, b, 0.42f),
        Blob(0.56f, 1.02f, 0.65f, androidx.compose.ui.graphics.lerp(a, b, 0.5f), 0.38f),
        Blob(0.44f, 0.44f, 0.28f, androidx.compose.ui.graphics.lerp(a, Color.White, 0.2f), 0.18f)
    ).map { if (style.startsWith("auto:")) Blob(it.x, it.y, it.r, it.color, it.a * 0.20f) else it }
}

/** "Match Theme" becomes the current theme's two accent colors (so the picture follows the theme); other choices stay as they are. */
@Composable
internal fun resolveBackdrop(style: String): String {
    val key = GuideBackdrops.normalize(style)
    if (key != "auto") return key
    val c = NuvioTheme.colors
    val a = androidx.compose.ui.graphics.lerp(c.Secondary, Color.Black, 0.25f)
    val b = androidx.compose.ui.graphics.lerp(c.SecondaryVariant, Color.Black, 0.25f)
    return "auto:${a.toArgb().toLong() and 0xFFFFFFFFL}:${b.toArgb().toLong() and 0xFFFFFFFFL}"
}

/** Paints the backdrop; scales with the screen (fixed-pixel sizes looked tiny on 4K, huge at 720p). */
@Composable
internal fun Modifier.guideBackdrop(styleKey: String): Modifier {
    val style = resolveBackdrop(styleKey)
    // One modifier per style, kept across redraws. Built fresh on every pass, its picture was
    // painted again (by the processor, five full-screen gradients) each time anything on the
    // screen changed: that was the guide's freezing.
    val painter = androidx.compose.runtime.remember(style) {
        Modifier.drawWithCache {
            // Painted once into a half-size picture, then just copied to the screen. The
            // gradients are soft, so the half-size picture looks the same.
            val w = size.width.toInt().coerceAtLeast(1)
            val h = size.height.toInt().coerceAtLeast(1)
            val bw = (w / 2).coerceAtLeast(1)
            val bh = (h / 2).coerceAtLeast(1)
            val bmp = androidx.compose.ui.graphics.ImageBitmap(bw, bh)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                this, layoutDirection, androidx.compose.ui.graphics.Canvas(bmp), androidx.compose.ui.geometry.Size(bw.toFloat(), bh.toFloat())
            ) { drawGuideBackdrop(style, bw.toFloat(), bh.toFloat()) }
            onDrawBehind {
                drawImage(bmp, dstSize = androidx.compose.ui.unit.IntSize(w, h))
            }
        }
    }
    return this.then(painter)
}

private fun backdropBrushes(style: String, w: Float, h: Float): List<Brush> {
    val big = maxOf(w, h)
    return backdropBlobs(style).map { b ->
        Brush.radialGradient(
            colors = listOf(b.color.copy(alpha = b.a), b.color.copy(alpha = b.a * 0.45f), Color.Transparent),
            center = Offset(w * b.x, h * b.y),
            radius = (big * b.r).coerceAtLeast(1f)
        )
    }
}

/**
 * The same backdrop drawn by hand over a [w] x [h] area (the whole screen): the preview window's
 * rounded corners are painted over the video's square corners with exactly what's behind them.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGuideBackdrop(style: String, w: Float, h: Float) {
    drawRect(backdropBase(style), size = androidx.compose.ui.geometry.Size(w, h))
    backdropBrushes(style, w, h).forEach { drawRect(it, size = androidx.compose.ui.geometry.Size(w, h)) }
}

/**
 * Guide tiles. The theme's raised and card surfaces, which carry its tint (warm for Amber, cool
 * for Ocean…), instead of its plain neutral greys, which looked flat. When "pure black surfaces"
 * makes them black, fixed dark greys keep the tiles visible.
 */
@Composable
internal fun guideSurface(): Color {
    // The same dark, see-through glass as the info panel at the top (glassColor): the theme's
    // background, not its lighter "elevated" grey, which read as a different, flatter material
    // next to it. The tiles also get a soft white edge (see ProgramBlock/channel cell) — the
    // alpha alone barely shows, the edge is what reads as "glass".
    val c = NuvioTheme.colors.Background
    val alpha = when (LocalGuideDarkness.current) { "solid" -> 0.94f; "standard" -> 0.50f; else -> 0.66f }
    return (if (c.luminance() < 0.003f) Color(0xFF0E0E12) else c).copy(alpha = alpha)
}

/** Shows on now, and the group on screen: a step lighter than [guideSurface] (same glass, lifted with white). */
@Composable
internal fun guideSurfaceVariant(): Color {
    val c = NuvioTheme.colors.Background
    val base = if (c.luminance() < 0.003f) Color(0xFF0E0E12) else c
    val alpha = when (LocalGuideDarkness.current) { "solid" -> 0.96f; "standard" -> 0.58f; else -> 0.72f }
    return androidx.compose.ui.graphics.lerp(base, Color.White, 0.10f).copy(alpha = alpha)
}

/** The faint edge every glass surface in the guide shares (tiles, groups, the info panel). */
internal val GuideGlassEdge = Color.White.copy(alpha = 0.10f)

/** Background / border / text colors for a focused or unfocused guide cell. */
internal data class LiveCellColors(val background: Color, val border: Color, val text: Color)

@Composable
internal fun liveCellColors(focused: Boolean, idle: Color, idleText: Color = NuvioTheme.colors.TextPrimary): LiveCellColors {
    if (!focused) return LiveCellColors(idle, Color.Transparent, idleText)
    return if (LocalLiveSolidHighlight.current) {
        LiveCellColors(NuvioTheme.colors.Secondary, Color.Transparent, NuvioTheme.colors.OnSecondary)
    } else {
        LiveCellColors(NuvioTheme.colors.FocusBackground, NuvioTheme.colors.FocusRing, NuvioTheme.colors.TextPrimary)
    }
}

/** A focusable row that highlights like TiviMate menus. Supports long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveFocusRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    content: @Composable RowScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val colors = liveCellColors(focused, if (selected) guideSurfaceVariant() else Color.Transparent)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.background)
            .border(2.dp, colors.border, RoundedCornerShape(8.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content(focused)
    }
}

@Composable
internal fun focusedTextColor(focused: Boolean): Color =
    if (focused && LocalLiveSolidHighlight.current) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary

@Composable
internal fun focusedSecondaryTextColor(focused: Boolean): Color =
    if (focused && LocalLiveSolidHighlight.current) NuvioTheme.colors.OnSecondary.copy(alpha = 0.8f)
    else NuvioTheme.colors.TextSecondary

/**
 * A channel logo, fitted (not cropped) into a wide box: logos are usually see-through pictures
 * of any shape. Shown as they are, with nothing behind them.
 */
@Composable
internal fun ChannelLogo(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(width = size * 1.6f, height = size),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank() && !DeadLogos.isDead(url)) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                onError = { DeadLogos.failed(url, it.result.throwable) }
            )
        }
    }
}

/**
 * Logo links the server said don't exist (404, 410…). Playlists are full of them, and the
 * picture loader doesn't remember failures: every time such a row scrolled back into view, the
 * logo was asked for again and failed again, a steady stream of wasted requests while scrolling
 * a big group. They're left alone for a while (30 minutes), then tried once more. Network
 * trouble (no connection yet, a timeout) doesn't count: those logos are tried again as usual.
 */
internal object DeadLogos {
    private const val RETRY_AFTER_MS = 30 * 60_000L
    private val dead = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isDead(url: String): Boolean {
        val at = dead[url] ?: return false
        if (System.currentTimeMillis() - at < RETRY_AFTER_MS) return true
        dead.remove(url)
        return false
    }

    fun failed(url: String, error: Throwable) {
        // Coil's HttpException reads "HTTP 404": only answers that mean "not there" count.
        val code = error.message?.trim()?.takeIf { error.javaClass.simpleName == "HttpException" }
            ?.removePrefix("HTTP")?.trim()?.take(3)?.toIntOrNull() ?: return
        if (code in 400..499 && code != 408 && code != 429) {
            if (dead.size > 5_000) dead.clear()
            dead[url] = System.currentTimeMillis()
        }
    }
}

/**
 * A soft glow in the theme's color behind the top of a screen (instead of show artwork, which
 * isn't always right): strongest at the top right, fading out before the bottom edge. Follows
 * the theme, kept fainter for the white theme (a white glow looks like grey haze).
 */
@Composable
internal fun ThemeGlow(modifier: Modifier = Modifier) {
    val accent = NuvioTheme.colors.Secondary
    val top = NuvioTheme.colors.BackgroundElevated
    Box(modifier = modifier.drawBehind { drawThemeGlow(accent, top, size.width, size.height) })
}

/**
 * The glow itself, for an area [w] x [h] from the top left. Also used to paint the preview's
 * rounded corners in exactly the colors behind them.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThemeGlow(accent: Color, top: Color, w: Float, h: Float) {
    val strength = if (accent.luminance() > 0.8f) 0.02f else 0.04f
    drawRect(
        Brush.verticalGradient(listOf(top.copy(alpha = 0.85f), Color.Transparent), startY = 0f, endY = h),
        size = androidx.compose.ui.geometry.Size(w, h)
    )
    val right = Offset(w * 0.80f, h * 0.04f)
    val r1 = h * 0.94f
    scale(scaleX = 2.4f, scaleY = 1f, pivot = right) {
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = strength), Color.Transparent), center = right, radius = r1), radius = r1, center = right)
    }
    val left = Offset(w * 0.10f, 0f)
    val r2 = h * 0.80f
    scale(scaleX = 2.2f, scaleY = 1f, pivot = left) {
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = strength * 0.4f), Color.Transparent), center = left, radius = r2), radius = r2, center = left)
    }
}

/** What a show's title says about it: on live, or new. Many guides only mark these in the title. */
internal data class TitleMarks(val title: String, val live: Boolean, val isNew: Boolean)

private val LIVE_MARK = Regex("""^\s*live\s*[:\-–|]\s*""", RegexOption.IGNORE_CASE)
private val NEW_MARK = Regex("""^\s*new\s*[:\-–|]\s*|\s*[(\[]\s*new\s*[)\]]\s*""", RegexOption.IGNORE_CASE)

/**
 * A small least-recently-used memory, safe from any thread. The guide draws the same few hundred
 * titles and times over and over (every scroll, every highlight move); working each one out
 * again each time was a lot of wasted work on slower TV boxes.
 */
internal class LiveMemo<K, V>(private val max: Int) {
    private val map = object : java.util.LinkedHashMap<K, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }
    @Synchronized fun get(key: K, make: (K) -> V): V = map[key] ?: make(key).also { map[key] = it }
}

private val titleMarksMemo = LiveMemo<String, TitleMarks>(4096)

internal fun titleMarks(title: String): TitleMarks = titleMarksMemo.get(title) { workOutTitleMarks(it) }

private fun workOutTitleMarks(title: String): TitleMarks {
    var t = title
    // Small raised "ᴸᴵⱽᴱ" / "ᴺᴱᵂ" in the title: the same LIVE / NEW tags as "LIVE:" / "(NEW)".
    val small = LiveTvSearchBridge.smallWords(t)
    val smallLive = "live" in small
    val smallNew = "new" in small
    if (smallLive || smallNew) t = LiveTvSearchBridge.withoutSmallWords(t, setOf("live", "new"))
    val live = LIVE_MARK.containsMatchIn(t) || smallLive
    if (live) t = t.replace(LIVE_MARK, "")
    val isNew = NEW_MARK.containsMatchIn(t) || smallNew
    if (isNew) t = t.replace(NEW_MARK, " ").trim()
    return TitleMarks(t.ifBlank { title }, live, isNew)
}

/** The red of the LIVE tag (the same everywhere, whatever the theme). */
internal val LIVE_RED = Color(0xFFE53935)

/** A small rounded tag on a program or in the info panel (LIVE, NEW, HD…). */
@Composable
internal fun LiveTag(text: String, background: Color, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(text = text, color = color, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** Centered modal panel used by the context menu, program info and text input dialogs. */
@Composable
internal fun LiveDialog(
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    content: @Composable () -> Unit
) {
    // A dialog opened by a long press appears while OK is still held. Swallow the rest of that
    // press (its repeats and its release) so it can't click the first menu item and close the menu.
    var sawKeyDown by remember { mutableStateOf(false) }
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent.keyCode
                    val okKey = k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER ||
                        k == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER || k == android.view.KeyEvent.KEYCODE_BUTTON_A
                    // Some remotes start a fresh "press" in the new window while the button is still
                    // held: any OK in the first moments is the same long press, never a new click.
                    val justOpened = okKey && android.os.SystemClock.uptimeMillis() - openedAt < 900
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (justOpened || (okKey && !sawKeyDown && e.nativeKeyEvent.repeatCount > 0)) {
                                true
                            } else {
                                sawKeyDown = true
                                false
                            }
                        }
                        KeyEventType.KeyUp -> justOpened || !sawKeyDown
                        else -> false
                    }
                }
                .widthIn(min = 320.dp, max = width)
                .heightIn(max = 620.dp)
                // The same dark glass as the play bar and the ⚙ panels.
                .glass(glassColor("darker"), RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            content()
        }
    }
}

@Composable
internal fun LivePlayerSurface(
    player: Player?,
    modifier: Modifier = Modifier,
    useSurfaceView: Boolean,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    /** Nuvio's picture-size mode (full screen only); null keeps the plain [resizeMode]. */
    aspectMode: com.nuvio.tv.ui.screens.player.AspectMode? = null,
    /** Called when this view takes over the picture (the player's watchdog checks it starts). */
    onAttached: () -> Unit = {},
    /** How to treat the stream's pixel-shape flag (fixes 24/7 movies that come out tall). */
    pictureShape: com.nuvio.tv.livetv.player.PictureShape = com.nuvio.tv.livetv.player.PictureShape.AUTO
) {
    val layout = if (useSurfaceView) R.layout.live_tv_surface_player_view else R.layout.live_tv_texture_player_view
    var viewRef by remember { mutableStateOf<PlayerView?>(null) }
    val currentMode = androidx.compose.runtime.rememberUpdatedState(aspectMode)
    val currentShape = androidx.compose.runtime.rememberUpdatedState(pictureShape)
    fun reshape(view: PlayerView) = view.post {
        com.nuvio.tv.livetv.player.LivePictureShape.apply(view, currentShape.value, currentMode.value)
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(layout, null) as PlayerView).also { viewRef = it }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
                if (player != null) onAttached()
            }
            view.resizeMode = if (aspectMode != null) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeMode
            reshape(view)
        }
    )
    // Re-apply when the video's shape changes (new channel, different resolution). Runs after the
    // player view's own sizing, which follows the stream's flag, and puts our shape back on top.
    DisposableEffect(viewRef, player) {
        val view = viewRef
        if (view == null || player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { reshape(view) }
            override fun onRenderedFirstFrame() { reshape(view) }
        }
        player.addListener(listener)
        val remove = com.nuvio.tv.ui.screens.player.addExoAspectLayoutChangeListener(view) { _, _, _, _, _, _, _, _, _ ->
            reshape(view)
        }
        reshape(view)
        onDispose { player.removeListener(listener); remove() }
    }
    androidx.compose.runtime.LaunchedEffect(pictureShape, aspectMode) { viewRef?.let { reshape(it) } }
    DisposableEffect(Unit) {
        onDispose { viewRef?.player = null }
    }
}

/** Pauses Live TV when the app goes to the background (Home button) and resumes on return. */
@Composable
internal fun PauseLiveTvInBackground(playback: com.nuvio.tv.livetv.player.LiveTvPlaybackController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(context, playback) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is androidx.lifecycle.LifecycleOwner) c = c.baseContext
        val lifecycle = (c as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // Pause as soon as the app stops being the one in front (not only once it's fully
                // hidden): some launchers, notably ones showing a live wallpaper, leave the app
                // "visible behind" them, so it never got the fully-hidden signal and kept playing.
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE, androidx.lifecycle.Lifecycle.Event.ON_STOP -> playback.onAppBackground()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> playback.onAppForeground()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
}

/**
 * Clock times ("7:30 PM"). Making a date formatter is slow (it loads the language's data), and
 * the guide asked for two per show on every redraw: formatters are kept (one per thread, as
 * they aren't thread-safe), and each minute's text is remembered.
 */
private val clockFormatters = ThreadLocal<HashMap<String, SimpleDateFormat>>()
private val clockMemo = LiveMemo<String, String>(2048)

private fun clockFormatter(pattern: String, locale: Locale, zone: java.util.TimeZone): SimpleDateFormat {
    val all = clockFormatters.get() ?: HashMap<String, SimpleDateFormat>().also { clockFormatters.set(it) }
    val key = pattern + '|' + locale + '|' + zone.id
    return all.getOrPut(key) { SimpleDateFormat(pattern, locale).also { it.timeZone = zone } }
}

internal fun formatClock(ms: Long, use24h: Boolean): String {
    val locale = Locale.getDefault()
    val zone = java.util.TimeZone.getDefault()
    val pattern = if (use24h) "HH:mm" else "h:mm a"
    // Seconds never show, so one text per minute (and per language and time zone).
    val minute = Math.floorDiv(ms, 60_000L)
    return clockMemo.get("$minute|$pattern|$locale|${zone.id}") {
        clockFormatter(pattern, locale, zone).format(Date(minute * 60_000L))
    }
}

internal fun formatRange(startMs: Long, stopMs: Long, use24h: Boolean): String =
    "${formatClock(startMs, use24h)} – ${formatClock(stopMs, use24h)}"

internal fun formatDayClock(ms: Long, now: Long, use24h: Boolean): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    val sameDay = day.format(Date(ms)) == day.format(Date(now))
    val prefix = if (sameDay) "" else SimpleDateFormat("EEE ", Locale.getDefault()).format(Date(ms))
    return prefix + formatClock(ms, use24h)
}

internal fun minutesLeftLabel(stopMs: Long, now: Long): String {
    val min = ((stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (min >= 60) "${min / 60}h ${min % 60}m left" else "${min}m left"
}

/**
 * True only once [value] has stayed true for [delayMs]: "Loading…" then shows only for channels
 * that are actually slow, not for the second or so every channel change takes.
 */
@Composable
internal fun rememberDelayedTrue(value: Boolean, delayMs: Long): Boolean {
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(value) {
        if (!value) { shown = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(delayMs)
        shown = true
    }
    return value && shown
}

/** Fetches a show's full details (description, cast…) when the guide only has its short form. */
internal val LocalShowDetails = androidx.compose.runtime.staticCompositionLocalOf<suspend (String, EpgProgram) -> EpgProgram> { { _, p -> p } }

/** [program] with its details filled in (shown straight away, details a moment later if needed). */
@Composable
internal fun rememberShowDetails(channelKey: String?, program: EpgProgram?): EpgProgram? {
    val loader = LocalShowDetails.current
    val state = androidx.compose.runtime.produceState(initialValue = program, channelKey, program) {
        value = program
        if (channelKey != null && program != null) value = loader(channelKey, program)
    }
    return state.value
}

/**
 * A guide description with the release year taken off its end ("… a can of Coke. (2009)"), and
 * the year to show next to the category instead, so it can't be cut off with the description.
 * Only a year in brackets, or one standing alone after the last sentence, counts: a year inside
 * the text ("…won the title in 1998.") stays where it is.
 */
internal data class DescriptionParts(val text: String?, val year: Int?)

private val TRAILING_YEAR = Regex("""\s*[,;·|\-–—]?\s*\(\s*((?:19|20)\d{2})\s*\)[\s.]*$""")
private val TRAILING_BARE_YEAR = Regex("""(?<=[.!?])\s+((?:19|20)\d{2})\.?\s*$""")

internal fun splitDescriptionYear(description: String?, year: Int?): DescriptionParts {
    val d = description?.trim()?.takeIf { it.isNotEmpty() } ?: return DescriptionParts(null, year)
    val m = TRAILING_YEAR.find(d) ?: TRAILING_BARE_YEAR.find(d)
    val found = m?.groupValues?.get(1)?.toIntOrNull()
    val text = if (m != null) d.substring(0, m.range.first).trimEnd() else d
    return DescriptionParts(text.ifEmpty { null }, year ?: found)
}

/** "S2 E5  ·  Drama  ·  2009": the year added unless the line already has it. */
internal fun metaWithYear(parts: List<String?>, year: Int?): String {
    val base = parts.filterNotNull().filter { it.isNotBlank() }
    val y = year?.toString()
    val withYear = if (y != null && base.none { it.contains(y) }) base + y else base
    return withYear.joinToString("  ·  ")
}

// ---------------------------------------------------------------- corner rounding

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface LiveCornerEntryPoint {
    fun layoutPreferences(): com.nuvio.tv.data.local.LayoutPreferenceDataStore
}

/**
 * Nuvio's poster corner setting (Settings › Layout), followed by Live TV's tiles, posters and
 * preview so they're rounded the same as the posters on the home screen. Per profile, like
 * Nuvio's own setting.
 */
internal object LiveCorners {
    private val _radius = kotlinx.coroutines.flow.MutableStateFlow(12)
    val radius: kotlinx.coroutines.flow.StateFlow<Int> = _radius
    @Volatile private var started = false

    fun start(context: android.content.Context) {
        if (started) return
        started = true
        val entry = runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, LiveCornerEntryPoint::class.java)
        }.getOrNull() ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { entry.layoutPreferences().posterCardCornerRadiusDp.collect { _radius.value = it.coerceIn(0, 40) } }
        }
    }
}

/** The corner rounding for Live TV's cards and tiles (the same as Nuvio's posters). */
@Composable
internal fun liveCorner(): androidx.compose.ui.unit.Dp {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember { LiveCorners.start(context) }
    return LiveCorners.radius.collectAsState().value.dp
}

/**
 * A message over the video ("Reconnecting…") with the reason underneath, wrapped onto as many
 * lines as it needs. A long reason ("Provider refused the stream (HTTP 403): account limit…")
 * used to sit on the same single line and got cut off, so you couldn't see what was wrong.
 */
@Composable
internal fun PlayerStatusMessage(
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    /** The small guide preview (smaller text) rather than full screen. */
    compact: Boolean = false
) {
    Column(
        modifier = modifier
            .padding(if (compact) 8.dp else 32.dp)
            .widthIn(max = if (compact) 480.dp else 760.dp)
            .clip(RoundedCornerShape(if (compact) 8.dp else 12.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = if (compact) 6.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
    ) {
        LiveText(
            title,
            color = Color.White,
            size = if (compact) 14.sp else 18.sp,
            weight = FontWeight.SemiBold,
            align = TextAlign.Center
        )
        if (!detail.isNullOrBlank()) {
            LiveText(
                detail,
                color = Color.White.copy(alpha = 0.8f),
                size = if (compact) 12.sp else 15.sp,
                maxLines = if (compact) 4 else 6,
                align = TextAlign.Center
            )
        }
    }
}



/**
 * A guide tile: fill and soft edge painted in one go from a shape worked out once. Replaces
 * clip + background + border, whose rounded clip made every tile cost extra on slower boxes
 * (a guide screen has well over a hundred tiles). Children are only clipped when asked to.
 */
@Composable
internal fun Modifier.glassTile(
    fill: Color,
    edge: Color?,
    shape: androidx.compose.ui.graphics.Shape,
    clipChildren: Boolean = false
): Modifier {
    val painter = androidx.compose.runtime.remember(fill, edge, shape) {
        Modifier.drawWithCache {
            val stroke = 2.dp.toPx()
            val fillOutline = shape.createOutline(size, layoutDirection, this)
            val edgeOutline = if (edge != null) {
                val inner = androidx.compose.ui.geometry.Size((size.width - stroke).coerceAtLeast(1f), (size.height - stroke).coerceAtLeast(1f))
                shape.createOutline(inner, layoutDirection, this)
            } else null
            onDrawBehind {
                paintOutline(fillOutline, fill, null)
                if (edge != null && edgeOutline != null) {
                    translate(stroke / 2f, stroke / 2f) {
                        paintOutline(edgeOutline, edge, androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    }
                }
            }
        }
    }
    return this.then(if (clipChildren) Modifier.clip(shape) else Modifier).then(painter)
}

/**
 * Counts slow screen frames while a screen is open and writes the tally to the Start-Up Report,
 * so "it feels laggy" can be told apart by what the screen was doing (and compared between versions).
 */
@Composable
internal fun FrameMeter(label: String) {
    androidx.compose.runtime.DisposableEffect(label) {
        val choreographer = android.view.Choreographer.getInstance()
        var last = 0L
        var total = 0; var slow = 0; var bad = 0; var worst = 0L
        var reportedAt = System.currentTimeMillis()
        // Where the screen was stuck: while a frame is held up, the main thread's current
        // position in the code is noted (a few times a second at most), and the most common
        // places go into the report.
        val stuckAt = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val mainThread = android.os.Looper.getMainLooper().thread
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        val watcher = Thread {
            while (alive.get()) {
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                main.post { answered.set(true) }
                try { Thread.sleep(90) } catch (_: InterruptedException) { return@Thread }
                if (!answered.get()) {
                    val st = mainThread.stackTrace
                    // The top of the stack, as it is (no filtering), with a repeated call shown once.
                    val spot = st.map { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
                        .fold(ArrayList<String>()) { acc, f -> if (acc.lastOrNull() != f) acc.add(f); acc }
                        .take(7).joinToString(" < ")
                    stuckAt.merge(spot, 1) { a, b -> a + b }
                }
                try { Thread.sleep(60) } catch (_: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true; it.name = "frame-watch"; it.start() }
        lateinit var cb: android.view.Choreographer.FrameCallback
        fun flush(final: Boolean) {
            if (total >= 30) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    "$label frames: $total drawn, $slow slower than 35 ms, $bad slower than 100 ms, worst $worst ms" + if (final) " (screen closed)" else ""
                )
                // How full the app's memory is (a full one makes everything stall while it cleans up).
                val rt = Runtime.getRuntime()
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    "  memory: ${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)} MB used of ${rt.maxMemory() / (1024 * 1024)} MB allowed"
                )
                stuckAt.entries.sortedByDescending { it.value }.take(3).forEach {
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add("  stuck x${it.value}: ${it.key}")
                }
            }
            stuckAt.clear()
            total = 0; slow = 0; bad = 0; worst = 0
            reportedAt = System.currentTimeMillis()
        }
        cb = android.view.Choreographer.FrameCallback { nanos ->
            if (last != 0L) {
                val ms = (nanos - last) / 1_000_000L
                // Long gaps are the screen sitting still, not slow drawing.
                if (ms in 1..700) {
                    total++
                    if (ms > 35) slow++
                    if (ms > 100) bad++
                    if (ms > worst) worst = ms
                }
            }
            last = nanos
            if (System.currentTimeMillis() - reportedAt > 20_000) flush(false)
            choreographer.postFrameCallback(cb)
        }
        choreographer.postFrameCallback(cb)
        onDispose { alive.set(false); watcher.interrupt(); choreographer.removeFrameCallback(cb); flush(true) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.paintOutline(
    outline: androidx.compose.ui.graphics.Outline,
    color: Color,
    style: androidx.compose.ui.graphics.drawscope.DrawStyle?
) {
    val st = style ?: androidx.compose.ui.graphics.drawscope.Fill
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Rectangle -> drawRect(color, outline.rect.topLeft, outline.rect.size, style = st)
        is androidx.compose.ui.graphics.Outline.Rounded -> {
            val r = outline.roundRect
            drawRoundRect(
                color, Offset(r.left, r.top), androidx.compose.ui.geometry.Size(r.width, r.height),
                androidx.compose.ui.geometry.CornerRadius(r.topLeftCornerRadius.x, r.topLeftCornerRadius.y), style = st
            )
        }
        is androidx.compose.ui.graphics.Outline.Generic -> drawPath(outline.path, color, style = st)
    }
}
