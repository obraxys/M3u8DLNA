package com.mavis.m3u8dlna.proxy

/**
 * Extension → MIME mapping for the handful of container types that show up in
 * HLS. Renderers are surprisingly picky: many refuse anything they do not
 * recognise, and NanoHTTPD would otherwise answer `application/octet-stream`.
 */
object MimeTypes {

    fun forUrl(url: String): String = when (extensionOf(url)) {
        "m3u8", "m3u" -> PLAYLIST
        "ts", "m2ts" -> "video/mp2t"
        "aac" -> "audio/aac"
        "mp4", "m4s", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "m3u8x" -> PLAYLIST
        "mp3" -> "audio/mpeg"
        "ac3", "eac3" -> "audio/ac3"
        "vtt" -> "text/vtt"
        "webvtt" -> "text/vtt"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "key", "bin", "idx" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    /** True for a file extension that should be preserved in a proxy URL. */
    fun hasKnownPlaylistExtension(url: String): Boolean {
        val ext = extensionOf(url)
        return ext == "m3u8" || ext == "m3u" || ext == "m3u8x"
    }

    /**
     * A short, alphanumeric file extension (max 5 chars, as in `.m3u8`) or "".
     * We keep it in the proxy URL so that a renderer receiving
     * `/p/<token>/r.m3u8?u=…` still sees a playlist-looking URL even when the
     * CDN hid the extension behind a query string.
     */
    fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        val ext = name.substring(dot + 1)
        if (ext.isEmpty() || ext.length > 5) return ""
        if (!ext.all { it.isLetterOrDigit() }) return ""
        return ext.lowercase()
    }

    const val PLAYLIST = "application/vnd.apple.mpegurl"
}
