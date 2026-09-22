package com.n7folder.player.ui.canvas

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n7folder.player.ui.theme.NeonCyan
import com.n7folder.player.ui.theme.NeonLime
import com.n7folder.player.ui.theme.NeonMagenta
import com.n7folder.player.ui.theme.TextPrimary
import com.n7folder.player.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp

private const val MIN_SCALE = 0.35f
private const val MAX_SCALE = 4f

/** Au-delà de ce zoom, relâcher les doigts "entre" dans l'artiste sous les doigts. */
private const val ENTER_SCALE = 2.4f

/** À partir de ce zoom, l'artiste sous les doigts est mis en évidence. */
private const val HIGHLIGHT_SCALE = 1.35f
private const val MIN_FLING_START = 200f
private const val MIN_FLING_STOP = 30f
private const val FLING_FRICTION = 3.2f

private val QUIET_COLORS = intArrayOf(TextPrimary.toArgb(), TextSecondary.toArgb())
private val LOUD_COLORS = intArrayOf(NeonCyan.toArgb(), NeonLime.toArgb(), NeonMagenta.toArgb())

/**
 * Surface interactive façon n7player : un nuage de tous les artistes (taille = nombre de pistes).
 *
 * - un doigt : défilement avec inertie ; deux doigts : zoom centré sur les doigts (0,35x à 4x) ;
 * - un appui ouvre l'artiste, un appui long ouvre le menu d'actions ;
 * - zoom sémantique : en agrandissant beaucoup sur un artiste puis en relâchant, on "entre" dedans
 *   (l'écran de ses pochettes s'ouvre) ;
 * - la mise en page (linéaire) est calculée hors du thread principal ; le dessin ne parcourt que
 *   les lignes visibles, avec du texte natif : fluide même avec des milliers d'artistes.
 *
 * @param focusLetter lettre à amener en haut de l'écran quand [focusNonce] change (barre A-Z)
 * @param layoutDelayMs attente avant de recalculer la mise en page (évite de tout refaire à chaque
 *   lot pendant l'analyse)
 */
@Composable
fun N7ZoomableSurface(
    words: List<CloudWord>,
    onWordTap: (String) -> Unit,
    onWordLongPress: (String) -> Unit,
    onZoomIntoWord: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusLetter: Char? = null,
    focusNonce: Int = 0,
    layoutDelayMs: Long = 0L
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val minFontPx = with(density) { 14.sp.toPx() }
    val maxFontPx = with(density) { 40.sp.toPx() }
    val padPx = with(density) { 12.dp.toPx() }
    val lineGapPx = with(density) { 6.dp.toPx() }
    val wordGapPx = with(density) { 12.dp.toPx() }
    val tapSlopPx = with(density) { 14.dp.toPx() }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var layout by remember { mutableStateOf<CloudLayout?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var focusIndex by remember { mutableIntStateOf(-1) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    val latestTap by rememberUpdatedState(onWordTap)
    val latestLongPress by rememberUpdatedState(onWordLongPress)
    val latestZoomInto by rememberUpdatedState(onZoomIntoWord)

    val drawPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    }

    // --- géométrie ---------------------------------------------------------------------------

    /** Garde le contenu dans l'écran : centré s'il est plus petit, borné sinon. */
    fun clampedOffset(s: Float, x: Float, y: Float): Offset {
        val current = layout ?: return Offset(x, y)
        val viewWidth = viewSize.width.toFloat()
        val viewHeight = viewSize.height.toFloat()
        val contentWidth = current.width * s
        val contentHeight = current.height * s
        val cx = if (contentWidth <= viewWidth) (viewWidth - contentWidth) / 2f else x.coerceIn(viewWidth - contentWidth, 0f)
        val cy = if (contentHeight <= viewHeight) 0f else y.coerceIn(viewHeight - contentHeight, 0f)
        return Offset(cx, cy)
    }

    /** Mot le plus proche d'un point de l'écran (tolérance large), ou -1. */
    fun wordNear(screen: Offset, slopPx: Float): Int {
        val current = layout ?: return -1
        return current.hit((screen.x - offsetX) / scale, (screen.y - offsetY) / scale, slopPx / scale)
    }

    fun applyTransform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = newScale / scale
        val nx = centroid.x - (centroid.x - offsetX) * ratio + pan.x
        val ny = centroid.y - (centroid.y - offsetY) * ratio + pan.y
        scale = newScale
        val target = clampedOffset(newScale, nx, ny)
        offsetX = target.x
        offsetY = target.y
        focusIndex = if (newScale >= HIGHLIGHT_SCALE) wordNear(centroid, 400f) else -1
    }

    fun startFling(vx: Float, vy: Float) {
        flingJob?.cancel()
        if (abs(vx) < MIN_FLING_START && abs(vy) < MIN_FLING_START) return
        flingJob = scope.launch {
            var velocityX = vx
            var velocityY = vy
            var last = withFrameNanos { it }
            while (isActive && (abs(velocityX) > MIN_FLING_STOP || abs(velocityY) > MIN_FLING_STOP)) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f
                last = now
                val target = clampedOffset(scale, offsetX + velocityX * dt, offsetY + velocityY * dt)
                if (target.x == offsetX) velocityX = 0f
                if (target.y == offsetY) velocityY = 0f
                offsetX = target.x
                offsetY = target.y
                val decay = exp(-FLING_FRICTION * dt)
                velocityX *= decay
                velocityY *= decay
            }
        }
    }

    fun onGestureEnd(velocity: Velocity, centroid: Offset) {
        val current = layout
        if (current != null && scale >= ENTER_SCALE) {
            val index = wordNear(centroid, 600f)
            if (index >= 0) {
                val word = current.words[index]
                // Retour à l'échelle 1, en gardant la ligne de l'artiste à la hauteur des doigts.
                scale = 1f
                val target = clampedOffset(1f, offsetX, centroid.y - word.baseline)
                offsetX = target.x
                offsetY = target.y
                focusIndex = -1
                latestZoomInto(word.key)
                return
            }
        }
        focusIndex = -1
        startFling(velocity.x, velocity.y)
    }

    fun keyAt(screen: Offset): String? {
        val current = layout ?: return null
        val index = wordNear(screen, tapSlopPx)
        return if (index >= 0) current.words[index].key else null
    }

    // --- mise en page hors du thread principal ----------------------------------------------

    LaunchedEffect(words, viewSize.width, minFontPx, maxFontPx) {
        val width = viewSize.width.toFloat()
        if (width <= 0f) return@LaunchedEffect
        if (layoutDelayMs > 0L) delay(layoutDelayMs)
        val computed = withContext(Dispatchers.Default) {
            val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            measurePaint.typeface = Typeface.DEFAULT_BOLD
            ArtistWordLayout.layout(words, width, padPx, minFontPx, maxFontPx, lineGapPx, wordGapPx) { text, font ->
                measurePaint.textSize = font
                measurePaint.measureText(text)
            }
        }
        layout = computed
        val target = clampedOffset(scale, offsetX, offsetY)
        offsetX = target.x
        offsetY = target.y
    }

    // --- saut à une lettre (barre A-Z) --------------------------------------------------------

    LaunchedEffect(focusNonce) {
        val letter = focusLetter ?: return@LaunchedEffect
        val current = layout ?: return@LaunchedEffect
        val index = current.firstIndexOfLetter(letter)
        if (index >= 0) {
            flingJob?.cancel()
            val word = current.words[index]
            val target = clampedOffset(scale, offsetX, viewSize.height * 0.12f - word.top * scale)
            offsetX = target.x
            offsetY = target.y
        }
    }

    // --- dessin + gestes ---------------------------------------------------------------------

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { position ->
                        val key = keyAt(position)
                        if (key != null) latestTap(key)
                    },
                    onLongPress = { position ->
                        val key = keyAt(position)
                        if (key != null) latestLongPress(key)
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    flingJob?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val touchSlop = viewConfiguration.touchSlop
                    var pastSlop = false
                    var accumulatedZoom = 1f
                    var accumulatedPan = Offset.Zero
                    var lastCentroid = down.position
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (!pastSlop) {
                            accumulatedZoom *= zoom
                            accumulatedPan += pan
                            val zoomMotion = abs(1f - accumulatedZoom) * event.calculateCentroidSize(useCurrent = false)
                            if (zoomMotion > touchSlop || accumulatedPan.getDistance() > touchSlop) pastSlop = true
                        }
                        if (pastSlop) {
                            if (zoom != 1f || pan != Offset.Zero) applyTransform(centroid, pan, zoom)
                            if (centroid.isSpecified) {
                                lastCentroid = centroid
                                tracker.addPosition(event.changes[0].uptimeMillis, centroid)
                            }
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (pastSlop) onGestureEnd(tracker.calculateVelocity(), lastCentroid)
                }
            }
    ) {
        val current = layout ?: return@Canvas
        val s = scale
        val ox = offsetX
        val oy = offsetY
        if (current.rowCount == 0) return@Canvas

        val firstRow = current.firstRowAtOrAfter(-oy / s)
        val lastRow = current.lastRowAtOrBefore((size.height - oy) / s)
        val highlighted = focusIndex
        val range = maxFontPx - minFontPx

        drawIntoCanvas { canvas ->
            val target = canvas.nativeCanvas
            var row = firstRow
            while (row <= lastRow && row < current.rowCount) {
                for (i in current.rowFirst[row] until current.rowEnd[row]) {
                    val word = current.words[i]
                    val screenX = word.x * s + ox
                    if (screenX > size.width || screenX + word.width * s < 0f) continue
                    val weight = if (range > 0f) (word.fontPx - minFontPx) / range else 0f
                    val hash = word.key.hashCode() and 0x7fffffff
                    drawPaint.color = when {
                        i == highlighted -> NeonMagenta.toArgb()
                        weight > 0.42f -> LOUD_COLORS[hash % LOUD_COLORS.size]
                        else -> QUIET_COLORS[hash % QUIET_COLORS.size]
                    }
                    drawPaint.textSize = word.fontPx * s
                    target.drawText(word.text, screenX, word.baseline * s + oy, drawPaint)
                }
                row++
            }
        }
    }
}
