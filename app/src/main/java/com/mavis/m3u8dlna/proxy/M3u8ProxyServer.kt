package com.mavis.m3u8dlna.proxy

import android.util.Log
import com.mavis.m3u8dlna.data.StreamHeaders
import com.mavis.m3u8dlna.util.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny HTTP server that re-serves one HLS stream to a DLNA renderer.
 *
 * Why bother, when a renderer could just be handed the original `.m3u8`?
 * Because the renderer makes its own HTTP requests, and it will not send the
 * `Referer` / `Cookie` / `User-Agent` that most CDN-hosted streams require.
 * Routing the stream through the phone makes header-protected links play on a
 * TV, and gives us one place where playlists get rewritten.
 *
 * Layout:
 *   GET /p/<token>/index.m3u8        → the (rewritten) master/media playlist
 *   GET /p/<token>/r.<ext>?u=<b64>   → a variant playlist, segment, key or map
 *
 * The token isolates concurrent cast sessions, so two TVs can watch two streams.
 */
class M3u8ProxyServer(
    private val clientFactory: () -> OkHttpClient,
) : NanoHTTPD(0) {

    data class Session(
        val token: String,
        val upstreamUrl: String,
        val headers: StreamHeaders,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val workerPool: ExecutorService = Executors.newFixedThreadPool(8, NamedThreads())

    @Volatile private var started = false
    private var baseUrl: String? = null

    init {
        setAsyncRunner(PoolAsyncRunner(workerPool))
    }

    /** Registers (or replaces) the stream served under [token]. */
    fun register(token: String, upstreamUrl: String, headers: StreamHeaders): String {
        sessions[token] = Session(token, upstreamUrl, headers)
        ensureStarted()
        return baseUrl.orEmpty()
    }

    fun unregister(token: String) {
        sessions.remove(token)
    }

    fun urlFor(token: String): String = baseUrl?.let { "$it/p/$token/index.m3u8" }.orEmpty()

    fun shutdown() {
        runCatching { stop() }
        workerPool.shutdownNow()
        sessions.clear()
        started = false
    }

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            runCatching { start(SOCKET_TIMEOUT_MS, true) }
                .onSuccess {
                    val port = listeningPort
                    val ip = NetworkUtils.localIpv4()
                    baseUrl = if (ip != null && port > 0) "http://$ip:$port" else null
                    if (baseUrl == null) {
                        Log.e(TAG, "Proxy bound to port $port but no LAN IPv4 address was found")
                    }
                    started = true
                }
                .onFailure { Log.e(TAG, "Proxy failed to start", it) }
        }
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        Log.w(TAG, "proxy error", e)
        text(500, "proxy error: ${e.message}")
    }

    private fun route(session: IHTTPSession): Response {
        val match = PATH.matchEntire(session.uri) ?: return text(404, "not found")
        val (token, kind) = match.destructured
        val active = sessions[token] ?: return text(404, "unknown session")

        return when (kind) {
            "index" ->
                if (session.method == Method.HEAD) {
                    text(200, "", MimeTypes.PLAYLIST)
                } else {
                    serveUpstream(session, active.upstreamUrl, active, forcePlaylist = true)
                }

            "r" -> {
                // NanoHTTPD has already percent-decoded the query string.
                val absolute = session.parms["u"]?.let { NetworkUtils.urlDecode(it) }
                    ?: return text(400, "missing or malformed 'u' parameter")
                if (session.method == Method.HEAD) {
                    text(200, "", MimeTypes.forUrl(absolute))
                } else {
                    serveUpstream(session, absolute, active, forcePlaylist = false)
                }
            }

            else -> text(404, "not found")
        }
    }

    /**
     * Fetches [url] upstream with the session's headers and relays it. Playlists
     * — by MIME type, by extension, or by sniffing the first bytes — are rewritten
     * on the way through so that nested resources are proxied as well.
     */
    private fun serveUpstream(
        session: IHTTPSession,
        url: String,
        active: Session,
        forcePlaylist: Boolean,
    ): Response {
        val builder = Request.Builder().url(url)
        active.headers.toMap().forEach { (name, value) -> builder.header(name, value) }

        // The renderer may ask for a byte range; pass it straight through.
        session.headers[RANGE_HEADER]?.takeIf { it.isNotBlank() }?.let {
            builder.header(RANGE_HEADER, it)
        }
        builder.method(if (session.method == Method.HEAD) "HEAD" else "GET", null)

        val upstream = runCatching { clientFactory().newCall(builder.build()).execute() }
            .getOrElse { error ->
                Log.w(TAG, "upstream request failed: ${error.message}")
                return text(502, "upstream error: ${error.message}")
            }

        val body = upstream.body
        if (!upstream.isSuccessful) {
            val detail = runCatching { body?.string()?.take(400).orEmpty() }.getOrDefault("")
            upstream.close()
            return text(upstream.code, detail.ifBlank { "upstream returned ${upstream.code}" })
        }
        if (body == null) {
            upstream.close()
            return text(502, "empty upstream body")
        }

        val contentType = upstream.header("Content-Type").orEmpty()
        val declaredLength = body.contentLength()
        val declaredPlaylist = forcePlaylist ||
            contentType.contains("mpegurl", ignoreCase = true) ||
            MimeTypes.hasKnownPlaylistExtension(url)

        if (declaredPlaylist) {
            // The whole playlist is buffered anyway, so the response can be closed
            // right away.
            val raw = runCatching { body.string() }.getOrElse {
                upstream.close()
                return text(502, "could not read playlist")
            }
            upstream.close()
            // After a redirect the base for relative URIs is the *final* URL.
            val finalBase = upstream.request.url.toString()
            val rewritten = PlaylistRewriter.rewrite(raw, finalBase) { absolute ->
                resourceUrl(active.token, absolute)
            }
            val bytes = rewritten.toByteArray(Charsets.UTF_8)
            return fixed(200, MimeTypes.PLAYLIST, ByteArrayInputStream(bytes), bytes.size.toLong())
        }

        // Binary resource: the response stays open until the renderer has read
        // every byte, so it must NOT be closed here.
        val sniffed = SniffingInputStream(body.byteStream(), PLAYLIST_SNIFF_BYTES)
        val mime = when {
            sniffed.sniffedAsPlaylist -> MimeTypes.PLAYLIST
            contentType.isNotBlank() -> contentType.substringBefore(';').trim()
            else -> MimeTypes.forUrl(url)
        }

        val relayed = ResponseStream(sniffed) { upstream.close() }

        return if (sniffed.sniffedAsPlaylist || declaredLength < 0) {
            // We consumed the head of the stream to sniff it, or upstream used
            // chunked encoding — the length is unknown, so stream it chunked.
            newChunkedResponse(CustomStatus(upstream.code), mime, relayed)
        } else {
            fixed(upstream.code, mime, relayed, declaredLength).also { response ->
                upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
                upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
            }
        }
    }

    private fun resourceUrl(token: String, absolute: String): String {
        // Keep the original extension: renderers that sniff by URL still see .m3u8.
        val extension = MimeTypes.extensionOf(absolute)
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        return "${baseUrl.orEmpty()}/p/$token/r$suffix?u=${NetworkUtils.urlEncode(absolute)}"
    }

    private fun fixed(status: Int, mime: String, data: InputStream, length: Long): Response =
        newFixedLengthResponse(CustomStatus(status), mime, data, length)

    private fun text(status: Int, body: String, mime: String = "text/plain"): Response {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(CustomStatus(status), mime, ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    /**
     * Peeks at the first bytes of a binary resource: CDNs love to serve playlists
     * from extension-less URLs, and a renderer handed the wrong MIME type will
     * simply refuse to play them.
     */
    private class SniffingInputStream(source: InputStream, private val limit: Int) :
        PushbackInputStream(source, limit) {

        var sniffedAsPlaylist: Boolean = false
            private set

        init {
            if (limit > 0) {
                val buffer = ByteArray(limit)
                val read = runCatching { read(buffer, 0, limit) }.getOrDefault(0)
                if (read > 0) {
                    val head = String(buffer, 0, read, Charsets.UTF_8)
                        .trimStart('﻿', ' ', '\n', '\r', '\t')
                    sniffedAsPlaylist = head.startsWith(PLAYLIST_MAGIC)
                    unread(buffer, 0, read)
                }
            }
        }
    }

    /**
     * Hands the upstream body to NanoHTTPD and releases the OkHttp connection
     * once the renderer has read it to the end — or the request dies. Without
     * this the response would be closed while the renderer is still pulling
     * bytes, and it would see a truncated stream.
     */
    private class ResponseStream(source: InputStream, private val onClose: () -> Unit) :
        FilterInputStream(source) {

        private var released = false

        private fun release() {
            if (released) return
            released = true
            runCatching { onClose() }
        }

        override fun read(): Int {
            val value = super.read()
            if (value == -1) release()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count == -1) release()
            return count
        }

        override fun close() {
            release()
            super.close()
        }
    }

    private class PoolAsyncRunner(private val pool: ExecutorService) : AsyncRunner {
        override fun closeAll() {
            pool.shutdownNow()
        }

        override fun closed(clientHandler: ClientHandler) = Unit

        override fun exec(clientHandler: ClientHandler) {
            runCatching { pool.execute(clientHandler) }
        }
    }

    private class NamedThreads : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread =
            Thread(r, "m3u8-proxy-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Lets us relay upstream status codes that the Status enum does not cover. */
    private class CustomStatus(private val code: Int) : NanoHTTPD.Response.IStatus {
        override fun getDescription(): String = "Status $code"
        override fun getRequestStatus(): Int = code
    }

    companion object {
        private const val TAG = "M3u8ProxyServer"
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val RANGE_HEADER = "Range"
        private const val PLAYLIST_SNIFF_BYTES = 32
        private const val PLAYLIST_MAGIC = "#EXTM3U"
        private val PATH = Regex("""^/p/([A-Za-z0-9_-]+)/([a-z]+)(?:\.[A-Za-z0-9]{1,5})?$""")

        fun newClient(connectTimeoutSec: Long = 10, readTimeoutSec: Long = 30): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .writeTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()

        fun newToken(): String = java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
