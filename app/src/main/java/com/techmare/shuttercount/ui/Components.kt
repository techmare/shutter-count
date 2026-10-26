package com.techmare.shuttercount.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import com.techmare.shuttercount.core.CameraModels
import com.techmare.shuttercount.data.HistoryEntry
import com.techmare.shuttercount.data.formatCount
import com.techmare.shuttercount.data.formatDate
import com.techmare.shuttercount.data.wearPercent
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

// ------------------------------------------------------------------ panel

/**
 * The graphite instrument panel at the top of each reading: camera name,
 * odometer, wear scale. Everything else on screen stays quiet around it.
 */
@Composable
fun InstrumentPanel(
    title: String,
    titleDim: Boolean = false,
    trailing: String? = null,
    count: Long?,
    caption: String,
    ratedLife: Int? = null,
    badge: String? = null,
    showWear: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = LocalAppColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.panel)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                title, Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = if (titleDim) c.panelMuted else c.panelText,
            )
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = c.panelMuted)
        }
        if (badge != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                badge,
                Modifier.clip(RoundedCornerShape(6.dp)).background(c.signal).padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium, color = Color(0xFF1C1F23),
            )
        }
        Spacer(Modifier.height(14.dp))
        Odometer(count)
        Spacer(Modifier.height(8.dp))
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = c.panelMuted)

        if (count != null && ratedLife != null && showWear) {
            Spacer(Modifier.height(16.dp))
            WearScale(count, ratedLife)
            Spacer(Modifier.height(12.dp))
            val wear = CameraModels.wear(count, ratedLife)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(wear.label, style = MaterialTheme.typography.titleLarge, color = c.panelText)
                Spacer(Modifier.width(10.dp))
                Text(
                    "${wearPercent(count, ratedLife)}% of rated life used",
                    Modifier.padding(bottom = 2.dp),
                    style = MaterialTheme.typography.bodyMedium, color = c.panelMuted,
                )
            }
        } else if (count != null && showWear) {
            Spacer(Modifier.height(10.dp))
            Text("Rated shutter life for this model isn't known, so wear can't be estimated.", style = MaterialTheme.typography.bodySmall, color = c.panelMuted)
        }
        footer?.invoke(this)
    }
}

// --------------------------------------------------------------- odometer

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Mechanical counter: each wheel rolls like a real odometer, higher wheels only
 * turning as the one below passes 9. Rolls up once when a new count arrives.
 */
@Composable
fun Odometer(value: Long?) {
    val c = LocalAppColors.current
    val reduced = rememberReducedMotion()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) {
        if (value == null) {
            anim.snapTo(0f)
        } else if (reduced) {
            anim.snapTo(value.toFloat())
        } else {
            anim.snapTo(0f)
            anim.animateTo(value.toFloat(), tween(durationMillis = 1500, easing = FastOutSlowInEasing))
        }
    }
    val target = value ?: 0L
    val wheels = max(6, target.toString().length)
    val significant = if (value == null || target == 0L) 0 else target.toString().length
    val v = if (value != null && anim.value >= target - 0.5f && !anim.isRunning) target.toDouble() else anim.value.toDouble()

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in wheels - 1 downTo 0) {
            val unit = 10.0.pow(i)
            val pos = if (i == 0) v else {
                val base = floor(v / unit)
                val rem = v - base * unit
                base + max(0.0, rem - (unit - 1))
            }
            Wheel(pos, dim = i >= significant, modifier = Modifier.weight(1f), colors = c)
            if (i % 3 == 0 && i != 0) Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun Wheel(position: Double, dim: Boolean, modifier: Modifier, colors: AppColors) {
    val height = 64.dp
    val hPx = with(LocalDensity.current) { height.toPx() }
    val whole = floor(position)
    val digit = (whole.toLong() % 10).toInt()
    val next = (digit + 1) % 10
    val frac = (position - whole).toFloat()
    val textColor = if (dim) Color(0xFF6B7178) else Color.White
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF15181B), 0.2f to Color(0xFF30353B), 0.5f to Color(0xFF3B4046),
                    0.8f to Color(0xFF30353B), 1f to Color(0xFF15181B),
                ),
            )
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        DigitText(digit, textColor, Modifier.offset { IntOffset(0, (-frac * hPx).roundToInt()) })
        if (frac > 0.001f) DigitText(next, textColor, Modifier.offset { IntOffset(0, ((1 - frac) * hPx).roundToInt()) })
    }
}

@Composable
private fun DigitText(d: Int, color: Color, modifier: Modifier) {
    Text(
        d.toString(), modifier,
        fontFamily = Fonts.condensed, fontWeight = FontWeight.Bold, fontSize = 46.sp, lineHeight = 46.sp, color = color,
    )
}

// ------------------------------------------------------------- wear scale

/** Ticked scale from 0 to the rated life, filled up to the current count. */
@Composable
fun WearScale(count: Long, ratedLife: Int) {
    val c = LocalAppColors.current
    val wear = CameraModels.wear(count, ratedLife)
    val fill = wearColor(wear)
    val fraction = (count.toFloat() / ratedLife).coerceIn(0f, 1f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val ticks = 40
            val tickW = 2.dp.toPx()
            val minor = 9.dp.toPx()
            val major = 16.dp.toPx()
            val baseY = major
            for (t in 0..ticks) {
                val x = t * (size.width - tickW) / ticks
                val h = if (t % 10 == 0) major else minor
                val on = t.toFloat() / ticks <= fraction && count > 0
                drawRoundRect(
                    if (on) fill else c.panelTick,
                    topLeft = Offset(x, baseY - h), size = Size(tickW, h), cornerRadius = CornerRadius(tickW / 2),
                )
            }
            val mx = fraction * (size.width - tickW) + tickW / 2
            val tri = Path().apply {
                moveTo(mx, baseY + 3.dp.toPx())
                lineTo(mx - 6.dp.toPx(), baseY + 11.dp.toPx())
                lineTo(mx + 6.dp.toPx(), baseY + 11.dp.toPx())
                close()
            }
            drawPath(tri, c.signal)
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0", style = MaterialTheme.typography.bodySmall, color = c.panelMuted)
            Spacer(Modifier.weight(1f))
            Text(formatCount(ratedLife / 2L), style = MaterialTheme.typography.bodySmall, color = c.panelMuted)
            Spacer(Modifier.weight(1f))
            Text("${formatCount(ratedLife.toLong())} rated", style = MaterialTheme.typography.bodySmall, color = c.panelMuted)
        }
    }
}

@Composable
fun wearColor(w: CameraModels.Wear): Color {
    val c = LocalAppColors.current
    return when (w) {
        CameraModels.Wear.LIKE_NEW, CameraModels.Wear.GOOD -> c.ok
        CameraModels.Wear.USED -> c.mid
        CameraModels.Wear.HIGH, CameraModels.Wear.BEYOND -> c.high
    }
}

// ---------------------------------------------------------- quiet blocks

/** Label/value rows separated by hairlines. */
@Composable
fun DetailRows(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    val c = LocalAppColors.current
    Column(Modifier.padding(horizontal = 8.dp)) {
        rows.forEachIndexed { i, (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Text(k, Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                Text(v, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
            if (i < rows.lastIndex) HorizontalDivider(color = c.rule)
        }
    }
}

/** A note with a coloured edge: red for problems, grey for guidance. */
@Composable
fun Note(text: String, warning: Boolean) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 8.dp)) {
        Box(
            Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                .background(if (warning) c.high else c.muted.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (warning) c.ink else c.muted)
    }
}

/** A short numbered how-to. Only used for real sequences. */
@Composable
fun Steps(title: String, steps: List<String>) {
    val c = LocalAppColors.current
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
        steps.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.titleSmall, color = c.muted)
                Text(s, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
    }
}

/** "Last checked …" line comparing with the previous reading of the same camera. */
fun previousLine(count: Long, previous: HistoryEntry?): Pair<String, String>? {
    previous ?: return null
    val diff = count - previous.count
    val sign = if (diff >= 0) "+" else "−"
    return "Last check" to "${formatCount(previous.count)} on ${formatDate(previous.time)} ($sign${formatCount(kotlin.math.abs(diff))})"
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share report"))
}
