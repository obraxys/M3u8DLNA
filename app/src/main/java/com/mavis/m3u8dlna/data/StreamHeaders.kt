package com.mavis.m3u8dlna.data

/**
 * Headers that the source server expects but a DLNA renderer will never send on
 * its own. This is the whole reason the proxy exists: an LG TV fetching an
 * `.m3u8` that requires `Referer` would just show a black screen.
 */
data class StreamHeaders(
    val userAgent: String = DEFAULT_USER_AGENT,
    val referer: String = "",
    val cookie: String = "",
    val origin: String = "",
) {
    fun toMap(): Map<String, String> = buildMap {
        if (userAgent.isNotBlank()) put("User-Agent", userAgent.trim())
        if (referer.isNotBlank()) put("Referer", referer.trim())
        if (cookie.isNotBlank()) put("Cookie", cookie.trim())
        if (origin.isNotBlank()) put("Origin", origin.trim())
    }

    val isEmpty: Boolean get() = userAgent.trim() == DEFAULT_USER_AGENT &&
        referer.isBlank() && cookie.isBlank() && origin.isBlank()

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

/** A previously played stream, kept in SharedPreferences as a tiny JSON array. */
data class HistoryItem(
    val url: String,
    val title: String,
    val timestamp: Long,
    val headers: StreamHeaders = StreamHeaders(),
)
