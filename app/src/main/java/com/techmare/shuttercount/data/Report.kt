package com.techmare.shuttercount.data

import com.techmare.shuttercount.core.CameraModels
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatCount(n: Long): String = NumberFormat.getIntegerInstance(Locale.FRANCE).format(n)
    .replace(' ', ' ').replace(' ', ' ')

fun formatDate(time: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(time))

fun wearPercent(count: Long, rated: Int): Int = ((count * 100.0) / rated).toInt()

private fun wearFr(w: CameraModels.Wear) = when (w) {
    CameraModels.Wear.LIKE_NEW -> "Comme neuf"
    CameraModels.Wear.GOOD -> "Bon état"
    CameraModels.Wear.USED -> "Usure moyenne"
    CameraModels.Wear.HIGH -> "Usure élevée"
    CameraModels.Wear.BEYOND -> "Au-delà de la durée estimée"
}

/** Customer-facing report, in French. */
fun buildReport(
    camera: String,
    serial: String?,
    count: Long,
    ratedLife: Int?,
    method: String,
    time: Long = System.currentTimeMillis(),
    experimental: Boolean = false,
): String = buildString {
    appendLine("📷 Rapport de déclenchements")
    appendLine("Appareil : $camera")
    if (!serial.isNullOrBlank()) appendLine("N° de série : $serial")
    appendLine("Déclenchements : ${formatCount(count)}")
    if (ratedLife != null) {
        appendLine("Durée de vie estimée de l'obturateur : ${formatCount(ratedLife.toLong())} (${wearPercent(count, ratedLife)} % utilisé)")
        appendLine("État : ${wearFr(CameraModels.wear(count, ratedLife))}")
    }
    appendLine("Méthode : $method")
    if (experimental) appendLine("(lecture expérimentale)")
    append("Date du contrôle : ${formatDate(time)}")
}
