package com.mavis.m3u8dlna.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Last-played streams, persisted as a tiny JSON array in SharedPreferences.
 * A Room/DataStore dependency would buy nothing for ten rows of text.
 */
class HistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("history", Context.MODE_PRIVATE)

    fun load(): List<HistoryItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                HistoryItem(
                    url = item.optString("url"),
                    title = item.optString("title"),
                    timestamp = item.optLong("ts"),
                    headers = StreamHeaders(
                        userAgent = item.optString("ua").ifBlank { StreamHeaders.DEFAULT_USER_AGENT },
                        referer = item.optString("ref"),
                        cookie = item.optString("cookie"),
                        origin = item.optString("origin"),
                    ),
                )
            }.filter { it.url.isNotBlank() }.sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())
    }

    fun remember(item: HistoryItem, limit: Int = 20): List<HistoryItem> {
        val updated = (listOf(item) + load().filter { it.url != item.url }).take(limit)
        persist(updated)
        return updated
    }

    fun clear(): List<HistoryItem> {
        prefs.edit().remove(KEY_ITEMS).apply()
        return emptyList()
    }

    private fun persist(items: List<HistoryItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("url", item.url)
                    .put("title", item.title)
                    .put("ts", item.timestamp)
                    .put("ua", item.headers.userAgent)
                    .put("ref", item.headers.referer)
                    .put("cookie", item.headers.cookie)
                    .put("origin", item.headers.origin),
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private companion object {
        const val KEY_ITEMS = "items"
    }
}
