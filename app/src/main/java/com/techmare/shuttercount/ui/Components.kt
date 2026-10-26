package com.techmare.shuttercount.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techmare.shuttercount.core.CameraModels
import com.techmare.shuttercount.data.HistoryEntry
import com.techmare.shuttercount.data.formatCount
import com.techmare.shuttercount.data.formatDate
import com.techmare.shuttercount.data.wearPercent

@Composable
fun CountCard(
    camera: String,
    count: Long,
    ratedLife: Int?,
    previous: HistoryEntry?,
    badge: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(camera, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (badge != null) {
                Spacer(Modifier.height(4.dp))
                Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                formatCount(count),
                fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text("shutter actuations", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)

            if (ratedLife != null) {
                val pct = wearPercent(count, ratedLife)
                val wear = CameraModels.wear(count, ratedLife)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                    color = wearColor(wear),
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${wear.label} · $pct% used", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("of ~${formatCount(ratedLife.toLong())}", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Rated shutter life for this model is unknown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            if (previous != null) {
                val diff = count - previous.count
                Spacer(Modifier.height(12.dp))
                Text(
                    "Last checked ${formatDate(previous.time)}: ${formatCount(previous.count)} (${if (diff >= 0) "+" else ""}${formatCount(diff)})",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
fun wearColor(w: CameraModels.Wear): Color = when (w) {
    CameraModels.Wear.LIKE_NEW, CameraModels.Wear.GOOD -> Color(0xFF2E7D32)
    CameraModels.Wear.USED -> Color(0xFFEF6C00)
    CameraModels.Wear.HIGH, CameraModels.Wear.BEYOND -> Color(0xFFC62828)
}

@Composable
fun DetailsCard(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(k, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun NoticeCard(text: String, warning: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (warning) Icons.Outlined.WarningAmber else Icons.Outlined.Info, null,
                Modifier.size(20.dp).padding(top = 2.dp),
                tint = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text, style = MaterialTheme.typography.bodyMedium,
                color = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun StepsCard(title: String, steps: List<String>) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            steps.forEachIndexed { i, s ->
                Row {
                    Text("${i + 1}.", Modifier.width(22.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share report"))
}
