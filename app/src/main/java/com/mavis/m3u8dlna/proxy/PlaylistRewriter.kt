package com.mavis.m3u8dlna.proxy

import java.net.URI

/**
 * Rewrites an HLS playlist so that every sub-resource (variant playlists,
 * segments, AES-128 keys, init maps, LL-HLS parts) is fetched through our local
 * proxy instead of directly from the origin.
 *
 * Everything that is not a URI is copied through byte-for-byte, so the renderer
 * sees a spec-valid playlist it can parse.
 */
object PlaylistRewriter {

    /**
     * Matches the `URI="…"` attribute of every HLS tag that carries one, plus
     * `SERVER-URI="…"` from EXT-X-CONTENT-STEERING. The lookbehind stops
     * `MY-URI="…"` or `SUBURI="…"` from being treated as a fetchable resource.
     */
    private val URI_ATTRIBUTE =
        Regex("""(?<![-A-Za-z0-9_])((?:SERVER-)?URI)="([^"]*)"""")

    private val TAG_NAME = Regex("""^#(EXT-X-[A-Z0-9-]+):""")

    /**
     * Tags whose quoted attribute values are free-form text and may legitimately
     * contain a `URI="…"`-looking substring. Rewriting those would corrupt the
     * playlist, so they are left alone.
     */
    private val TAGS_WITHOUT_FETCHABLE_URI = setOf("EXT-X-DEFINE", "EXT-X-DATERANGE")

    /** Schemes the client (or the DRM module) handles itself — we must not proxy them. */
    private val CLIENT_HANDLED_SCHEMES = listOf(
        "data:", "skd:", "urn:", "asset:", "cid:", "rtsp:", "rtsps:", "about:", "blob:",
    )

    /**
     * @param playlist raw playlist body as returned by the origin
     * @param baseUrl  URL the playlist was *finally* loaded from (after redirects),
     *                 used to resolve relative URIs
     * @param encode   maps an absolute upstream URL to the proxy URL the renderer should use
     */
    fun rewrite(
        playlist: String,
        baseUrl: String,
        encode: (String) -> String,
    ): String {
        val lines = playlist.split('\n')
        val out = StringBuilder(playlist.length + 512)

        lines.forEachIndexed { index, raw ->
            if (index > 0) out.append('\n')
            var line = raw
            if (index == 0) line = line.removePrefix("\uFEFF") // some CDNs prepend a BOM
            out.append(rewriteLine(line, baseUrl, encode))
        }

        // split() drops the information that the source ended with a newline.
        if (playlist.endsWith("\n")) out.append('\n')
        return out.toString()
    }

    fun rewriteLine(
        rawLine: String,
        baseUrl: String,
        encode: (String) -> String,
    ): String {
        val line = rawLine.trimEnd('\r')
        val trimmed = line.trimStart()
        return when {
            trimmed.isEmpty() -> line
            trimmed.startsWith("#") -> rewriteTagLine(line, baseUrl, encode)
            else -> mapUri(trimmed, baseUrl, encode) // a bare line is a media segment
        }
    }

    private fun rewriteTagLine(
        line: String,
        baseUrl: String,
        encode: (String) -> String,
    ): String {
        if (!line.contains("URI=")) return line
        val tag = TAG_NAME.find(line)?.groupValues?.get(1)
        if (tag != null && tag in TAGS_WITHOUT_FETCHABLE_URI) return line

        return URI_ATTRIBUTE.replace(line) { match ->
            val attribute = match.groupValues[1]
            val resolved = mapUri(match.groupValues[2], baseUrl, encode)
            "$attribute=\"$resolved\""
        }
    }

    /**
     * Resolves [value] against [baseUrl] and hands the absolute URL to [encode].
     * Anything unparseable or client-handled is returned untouched — a broken
     * line we pass through is better than a playlist we corrupt.
     */
    private fun mapUri(
        value: String,
        baseUrl: String,
        encode: (String) -> String,
    ): String {
        val candidate = value.trim()
        if (candidate.isEmpty()) return value
        if (isClientHandled(candidate)) return value

        val absolute = try {
            URI(baseUrl).resolve(candidate).toString()
        } catch (_: IllegalArgumentException) {
            return value
        } catch (_: Exception) {
            return value
        }

        if (isClientHandled(absolute)) return value
        return encode(absolute)
    }

    private fun isClientHandled(url: String): Boolean {
        val lower = url.lowercase()
        return CLIENT_HANDLED_SCHEMES.any { lower.startsWith(it) }
    }
}
