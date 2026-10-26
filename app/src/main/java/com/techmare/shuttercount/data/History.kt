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
                put("source", e.source); put("note", e.note); put("experimental", e.experimental)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "entries"
        const val MAX = 500
    }
}
