package com.techmare.shuttercount.data

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatCount(n: Long): String = NumberFormat.getIntegerInstance(Locale.FRANCE).format(n)
    .replace(' ', ' ').replace(' ', ' ')

fun formatDate(time: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(time))

fun wearPercent(count: Long, rated: Int): Int = ((count * 100.0) / rated).toInt()

/**
 * The wear percentage as shown in the app: a used camera never reads as "0%", and one past its
 * rating never reads as a bare "100%" next to "Beyond rating".
 */
fun wearPercentText(count: Long, rated: Int): String {
    val p = wearPercent(count, rated)
    return when {
        p == 0 && count > 0 -> "Under 1%"
        p == 100 && count > rated -> "Over 100%"
        else -> "$p%"
    }
}
