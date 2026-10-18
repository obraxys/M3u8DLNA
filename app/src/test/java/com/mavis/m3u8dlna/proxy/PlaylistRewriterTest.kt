package com.mavis.m3u8dlna.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRewriterTest {

    private val base = "https://cdn.example.com/live/stream/720p/index.m3u8"

    /** Prefixes every proxied URL so assertions can tell "rewritten" from "left alone". */
    private val encode: (String) -> String = { "http://10.0.0.2:8099/p/tok/r?u=$it" }

    private fun rewrite(playlist: String, baseUrl: String = base) =
        PlaylistRewriter.rewrite(playlist, baseUrl, encode)

    @Test
    fun `relative segment resolves against the playlist url`() {
        val result = rewrite("#EXTM3U\n#EXTINF:4.0,\nseg1.ts\n")
        assertTrue(result.contains("http://10.0.0.2:8099/p/tok/r?u=https://cdn.example.com/live/stream/720p/seg1.ts"))
    }

    @Test
    fun `parent relative segment resolves correctly`() {
        val result = rewrite("#EXTM3U\n#EXTINF:4.0,\n../audio/seg1.aac\n")
        assertTrue(result.contains("https://cdn.example.com/live/stream/audio/seg1.aac"))
    }

    @Test
    fun `absolute segment stays on the same absolute url`() {
        val result = rewrite("#EXTM3U\n#EXTINF:4.0,\nhttps://other.example.net/x/seg2.ts\n")
        assertTrue(result.contains("https://other.example.net/x/seg2.ts"))
        assertTrue(result.contains("/p/tok/r?u="))
    }

    @Test
    fun `aes key uri is proxied`() {
        val result = rewrite("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x0f\n")
        assertTrue(result.contains("URI=\"http://10.0.0.2:8099/p/tok/r?u=https://cdn.example.com/live/stream/720p/key.bin\""))
    }

    @Test
    fun `key with method none has no uri and is untouched`() {
        val line = "#EXT-X-KEY:METHOD=NONE"
        assertEquals(line, rewrite(line))
    }

    @Test
    fun `init map uri is proxied`() {
        val result = rewrite("#EXTINF:4.0,\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"1200@0\"\n")
        assertTrue(result.contains("URI=\"http://10.0.0.2:8099/p/tok/r?u=https://cdn.example.com/live/stream/720p/init.mp4\""))
        assertTrue(result.contains("BYTERANGE=\"1200@0\""))
    }

    @Test
    fun `alternate renditions are proxied but renditionless ones are left alone`() {
        val playlist = "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"ru\",URI=\"audio/ru.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\"\n"
        val result = rewrite(playlist)
        assertTrue(result.contains("URI=\"http://10.0.0.2:8099/p/tok/r?u=https://cdn.example.com/live/stream/720p/audio/ru.m3u8\""))
        assertTrue(result.contains("NAME=\"en\""))
    }

    @Test
    fun `ll-hls part and preload hint are proxied`() {
        val result = rewrite(
            "#EXTM3U\n#EXT-X-PART:DURATION=1.0,URI=\"part1.mp4\"\n" +
                "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"part2.mp4\"\n",
        )
        assertTrue(result.contains("URI=\"http://10.0.0.2:8099/p/tok/r?u=https://cdn.example.com/live/stream/720p/part1.mp4\""))
        assertTrue(result.contains("URI=\"http://10.0.0.2:8099/p/tok/r?u=https://cdn.example.com/live/stream/720p/part2.mp4\""))
    }

    @Test
    fun `content steering server-uri is proxied`() {
        val result = rewrite("#EXT-X-CONTENT-STEERING:SERVER-URI=\"https://steer.example.com/ssai\",SERVER-URI-TTL=30\n")
        assertTrue(result.contains("SERVER-URI=\"http://10.0.0.2:8099/p/tok/r?u=https://steer.example.com/ssai\""))
    }

    @Test
    fun `drm key uri is left to the client`() {
        val line = "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\""
        assertEquals(line, rewrite(line))
    }

    @Test
    fun `inline data uri is left alone`() {
        val line = "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"data:text/plain;base64,AAAA\",KEYFORMAT=\"identity\""
        assertEquals(line, rewrite(line))
    }

    @Test
    fun `ext-x-define is never rewritten even when a value looks like a uri attribute`() {
        val line = "#EXT-X-DEFINE:NAME=\"host\",VALUE=\"URI=\\\"https://evil\\\"\""
        assertEquals(line, rewrite(line))
    }

    @Test
    fun `extinf and other metadata lines survive byte for byte`() {
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n" +
            "#EXT-X-MEDIA-SEQUENCE:42\n#EXT-X-DISCONTINUITY\n" +
            "#EXTINF:4.000000,title=\"A, B & C\"\n"
        val result = rewrite(playlist)
        assertTrue(result.contains("#EXT-X-VERSION:3"))
        assertTrue(result.contains("#EXTINF:4.000000,title=\"A, B & C\""))
    }

    @Test
    fun `blank lines and comments are preserved`() {
        val playlist = "#EXTM3U\n\n# a comment\n#EXTINF:4.0,\nseg.ts\n"
        val result = rewrite(playlist)
        assertTrue(result.contains("\n\n# a comment"))
        assertTrue(result.contains("#EXTINF:4.0,\n"))
    }

    @Test
    fun `carriage returns are normalised and trailing newline is kept`() {
        val result = rewrite("#EXTM3U\r\n#EXTINF:4.0,\r\nseg.ts\r\n")
        assertTrue(result.endsWith("\n"))
        assertTrue(!result.contains("\r"))
    }

    @Test
    fun `bom does not turn the first tag into a segment uri`() {
        val result = rewrite("\uFEFF#EXTM3U\n#EXTINF:4.0,\nseg.ts\n")
        assertTrue(result.startsWith("#EXTM3U"))
    }

    @Test
    fun `unparseable uri is passed through instead of corrupting the playlist`() {
        val result = rewrite("#EXTINF:4.0,\nseg with space.ts\n")
        assertTrue(result.contains("seg with space.ts"))
    }

    @Test
    fun `query strings survive encoding`() {
        val result = rewrite("#EXTINF:4.0,\nseg.ts?token=abc&x=1\n")
        assertTrue(result.contains("seg.ts%3Ftoken%3Dabc%26x%3D1") || result.contains("seg.ts?token=abc&x=1"))
    }
}
