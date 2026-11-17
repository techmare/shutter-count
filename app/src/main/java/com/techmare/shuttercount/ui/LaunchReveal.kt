package com.techmare.shuttercount.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.techmare.shuttercount.R
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** True while the opening animation covers the app; screens use it to time their own entrance. */
val LocalLaunching = compositionLocalOf { false }

/**
 * The opening animation, played over [content] on a fresh start from the launcher.
 *
 * It begins as a copy of the system splash screen (the aperture on the icon's dark background,
 * see values-v31/themes.xml), so the two join without a jump. Then, like a shutter:
 *
 *  1. the aperture pulls in and its blades close a little;
 *  2. the blades swing open and the whole aperture rushes toward the viewer. The app is seen
 *     through the widening opening, which grows until it is larger than the screen.
 *
 * The app behind settles from slightly small to full size as it is uncovered. The aperture is
 * drawn as shapes at every size, so it stays sharp however far it zooms.
 */
@Composable
fun LaunchReveal(active: Boolean, onDone: () -> Unit, content: @Composable () -> Unit) {
    val pull = remember { Animatable(0f) }   // 0..1: the pull-in
    val open = remember { Animatable(0f) }   // 0..1: opening and zooming past
    val view = LocalView.current
    val done by rememberUpdatedState(onDone)
    val dark = colorResource(R.color.icon_bg)

    if (active) {
        LaunchedEffect(Unit) {
            delay(HOLD_MS)
            view.buzzPullIn()
            pull.animateTo(1f, tween(PULL_MS, easing = FastOutSlowInEasing))
            view.buzzOpen()
            open.animateTo(1f, tween(OPEN_MS, easing = LinearEasing))
            done()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val s = if (active) CONTENT_FROM + (1f - CONTENT_FROM) * easeOut(open.value) else 1f
                scaleX = s; scaleY = s
            },
        ) { content() }
        if (active) Canvas(Modifier.fillMaxSize()) { drawAperture(pull.value, open.value, dark) }
    }
}

/**
 * The launcher icon's aperture (res/drawable/ic_launcher_foreground.xml), on its 108-unit grid:
 * a ring, and six blades between an outer circle and a hexagonal opening. Around it, a dark
 * screen with that opening cut out of it.
 */
private fun DrawScope.drawAperture(pull: Float, open: Float, dark: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    // 288dp is the size the system splash draws an icon that has no background of its own
    val unit = 288.dp.toPx() / 108f

    // zoom: in to PULL_SCALE, then out until the opening's inner circle passes the screen corners
    val reach = hypot(cx, cy) * 1.08f
    val zoomEnd = reach / (HOLE_OPEN * COS_30 * unit)
    val zoom = if (open == 0f) 1f + (PULL_SCALE - 1f) * pull else PULL_SCALE * (zoomEnd / PULL_SCALE).pow(open * open)
    val u = unit * zoom

    // the hexagonal opening: closes a little on the pull-in, then swings wide early in the zoom
    val hole = if (open == 0f) HOLE_REST + (HOLE_PULLED - HOLE_REST) * pull else HOLE_PULLED + (HOLE_OPEN - HOLE_PULLED) * easeOut(open)
    val twist = -TWIST_PULL * pull + TWIST_OPEN * easeOut(open)

    fun at(degrees: Float, radius: Float): Offset {
        val a = Math.toRadians((degrees + twist).toDouble())
        return Offset(cx + (cos(a) * radius * u).toFloat(), cy + (sin(a) * radius * u).toFloat())
    }

    val opening = Path().apply {
        for (k in 0 until 6) at(-70f + 60f * k, hole).let { if (k == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
        close()
    }
    // the dark screen, with the opening cut out
    drawPath(
        Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addPath(opening)
        },
        dark,
    )
    // at rest the opening is dark like the icon; it clears as the blades begin to open
    val glass = 1f - (open / GLASS_CLEARS_BY).coerceIn(0f, 1f)
    if (glass > 0f) drawPath(opening, dark, alpha = glass)

    val outer = Rect(center = Offset(cx, cy), radius = BLADE_RADIUS * u)
    for (k in 0 until 6) {
        val start = -90f + 60f * k
        val blade = Path().apply {
            at(start, BLADE_RADIUS).let { moveTo(it.x, it.y) }
            arcTo(outer, start + twist, 60f, forceMoveTo = false)
            at(start + 80f, hole).let { lineTo(it.x, it.y) }
            at(start + 20f, hole).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(blade, if (k % 2 == 0) BLADE_LIGHT else BLADE_DARK)
    }
    drawCircle(BLADE_LIGHT, radius = RING_RADIUS * u, center = Offset(cx, cy), style = Stroke(width = RING_STROKE * u))
}

private fun easeOut(x: Float): Float = 1f - (1f - x).pow(3)

// timing: about 0.47 s from the hand-over to the app
private const val HOLD_MS = 40L
private const val PULL_MS = 150
private const val OPEN_MS = 280

// motion
private const val PULL_SCALE = 0.86f      // how far the aperture pulls in
private const val CONTENT_FROM = 0.94f    // the app settles from this size to full size
private const val TWIST_PULL = 14f        // degrees the blades turn while closing
private const val TWIST_OPEN = 58f        // degrees they swing while opening
private const val GLASS_CLEARS_BY = 0.3f  // share of the opening by which the app is fully visible through it

// the icon's geometry, in its 108-unit grid
private const val RING_RADIUS = 30f
private const val RING_STROKE = 3f
private const val BLADE_RADIUS = 26f
private const val HOLE_REST = 10f
private const val HOLE_PULLED = 7f
private const val HOLE_OPEN = 24f
private const val COS_30 = 0.8660254f
private val BLADE_LIGHT = Color(0xFFF5B400)
private val BLADE_DARK = Color(0xFFE0A300)
