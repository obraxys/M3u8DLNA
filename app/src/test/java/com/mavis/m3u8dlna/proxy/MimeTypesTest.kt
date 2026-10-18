package com.mavis.m3u8dlna.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypesTest {

    @Test
    fun `knows the playlist extensions`() {
        assertEquals(MimeTypes.PLAYLIST, MimeTypes.forUrl("https://x/y/index.m3u8"))
        assertEquals(MimeTypes.PLAYLIST, MimeTypes.forUrl("https://x/y/index.M3U8?token=1"))
        assertTrue(MimeTypes.hasKnownPlaylistExtension("https://x/master.m3u8"))
        assertTrue(!MimeTypes.hasKnownPlaylistExtension("https://x/segment.ts"))
    }

    @Test
    fun `maps transport segments`() {
        assertEquals("video/mp2t", MimeTypes.forUrl("https://x/seg1.ts"))
        assertEquals("video/mp4", MimeTypes.forUrl("https://x/init.mp4"))
        assertEquals("audio/aac", MimeTypes.forUrl("https://x/a.aac"))
        assertEquals("application/octet-stream", MimeTypes.forUrl("https://x/enc.key"))
    }

    @Test
    fun `extension survives query strings and fragments`() {
        assertEquals("ts", MimeTypes.extensionOf("https://x/seg1.ts?token=abc"))
        assertEquals("m3u8", MimeTypes.extensionOf("https://x/master.m3u8#frag"))
    }

    @Test
    fun `refuses to invent a suffix for extension-less or odd urls`() {
        assertEquals("", MimeTypes.extensionOf("https://x/stream/12345"))
        assertEquals("", MimeTypes.extensionOf("https://x/segment.verylongextension"))
        assertEquals("", MimeTypes.extensionOf("https://x/"))
    }
}
