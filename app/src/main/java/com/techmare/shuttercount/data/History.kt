package com.techmare.shuttercount.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val id: Long,
    val time: Long,
    val camera: String,
    val serial: String?,
    val count: Long,
    val ratedLife: Int?,
    /** "Photo" or "USB". */
    val source: String,
    val note: String = "",
    val experimental: Boolean = false,
    val ratedOfficial: Boolean = false,
)

/** Saved readings, newest first, kept in SharedPreferences as JSON. */
class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    id = o.getLong("id"),
                    time = o.getLong("time"),
                    camera = o.getString("camera"),
                    serial = o.optString("serial").ifEmpty { null },
                    count = o.getLong("count"),
                    ratedLife = if (o.has("rated")) o.getInt("rated") else null,
                    source = o.optString("source", "Photo"),
                    note = o.optString("note"),
                    experimental = o.optBoolean("experimental"),
                    ratedOfficial = o.optBoolean("ratedOfficial"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.take(MAX).forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("time", e.time); put("camera", e.camera)
                put("serial", e.serial ?: ""); put("count", e.count)
                e.ratedLife?.let { put("rated", it) }
                put("source", e.source); put("note", e.note); put("experimental", e.experimental); put("ratedOfficial", e.ratedOfficial)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "entries"
        const val MAX = 500
    }
}

/**
 * History search: every word must appear in the camera, serial or note (any case). A serial
 * matches with or without its spaces, and "R50" finds "Canon EOS R50".
 */
fun HistoryEntry.matches(query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val hay = "$camera ${serial.orEmpty()} $note ${if (source == "USB") "usb" else "photo"}".lowercase()
    val digits = serial.orEmpty().filter { it.isLetterOrDigit() }.lowercase()
    val joined = query.lowercase().filter { it.isLetterOrDigit() }
    return words.all { hay.contains(it) } || (joined.length >= 4 && digits.contains(joined))
}
