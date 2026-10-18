package com.mavis.m3u8dlna.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mavis.m3u8dlna.data.StreamHeaders

/**
 * Phone-side playback. Same HLS stack as the cast path, with the stream's
 * headers applied to every request the player makes.
 *
 * The ExoPlayer instance is created once and the HTTP factory's request
 * properties are swapped before each `prepare()`, which is cheaper than
 * rebuilding the player for every new link.
 */
class LocalPlayer(context: Context) {

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(StreamHeaders.DEFAULT_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
        )
        .build()

    fun play(url: String, title: String, headers: StreamHeaders) {
        httpFactory.setDefaultRequestProperties(headers.toMap())
        httpFactory.setUserAgent(
            headers.toMap()["User-Agent"] ?: StreamHeaders.DEFAULT_USER_AGENT,
        )

        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title.ifBlank { "HLS" }).build())
            .build()

        player.setMediaItem(item)
        player.playWhenReady = true
        player.prepare()
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    val isPlaying: Boolean get() = player.isPlaying

    fun release() {
        player.release()
    }
}
