package com.techmare.shuttercount.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.techmare.shuttercount.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The opening animation, drawn over the app on a fresh start from the launcher.
 *
 * It begins as a copy of the system splash screen (the aperture on the icon's dark background,
 * see values-v31/themes.xml), so the two join without a jump. The aperture then pulls in slightly
 * and rushes toward the viewer while the dark background clears, leaving the app behind it.
 */
@Composable
fun LaunchReveal(onDone: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val veil = remember { Animatable(1f) }   // opacity of the dark background
    val mark = remember { Animatable(1f) }   // opacity of the aperture
    val dark = colorResource(R.color.icon_bg)

    LaunchedEffect(Unit) {
        delay(HOLD_MS)
        scale.animateTo(0.82f, tween(WIND_UP_MS, easing = FastOutSlowInEasing))
        coroutineScope {
            launch { scale.animateTo(ZOOM, tween(ZOOM_MS, easing = CubicBezierEasing(0.7f, 0f, 0.84f, 0f))) }
            launch { veil.animateTo(0f, tween(ZOOM_MS * 2 / 3, delayMillis = ZOOM_MS / 6, easing = LinearEasing)) }
            launch { mark.animateTo(0f, tween(ZOOM_MS / 3, delayMillis = ZOOM_MS * 2 / 3, easing = LinearEasing)) }
        }
        onDone()
    }

    Box(
        Modifier.fillMaxSize().drawBehind { drawRect(dark, alpha = veil.value) },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
            // 288dp is the size the system splash draws an icon that has no background of its own
            modifier = Modifier.requiredSize(288.dp).graphicsLayer {
                scaleX = scale.value; scaleY = scale.value; alpha = mark.value
            },
        )
    }
}

private const val HOLD_MS = 120L
private const val WIND_UP_MS = 240
private const val ZOOM_MS = 380
private const val ZOOM = 30f
