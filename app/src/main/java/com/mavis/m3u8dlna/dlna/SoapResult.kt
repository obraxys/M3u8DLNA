package com.mavis.m3u8dlna.dlna

/**
 * Result of a UPnP control call. A 200 with a SOAP fault body is a failure, and
 * so is a 500 with an HTML body from a router that pretends to be a renderer —
 * both are worth surfacing to the user instead of a silent no-op.
 */
sealed class SoapResult {
    data class Success(val body: String) : SoapResult()
    data class Failure(val httpCode: Int, val message: String) : SoapResult()

    val successOrNull: SoapResult.Success? get() = this as? SoapResult.Success
    val errorText: String? get() = (this as? Failure)?.message
}

data class TransportInfo(
    val state: String,
    val status: String = "",
) {
    val isPlaying: Boolean get() = state.equals("PLAYING", ignoreCase = true)
    val isPaused: Boolean get() = state.equals("PAUSED_PLAYBACK", ignoreCase = true)
    val isStopped: Boolean get() = state.equals("STOPPED", ignoreCase = true)
    val isTransitioning: Boolean get() = state.equals("TRANSITIONING", ignoreCase = true)
    val hasNoMedia: Boolean get() = state.equals("NO_MEDIA_PRESENT", ignoreCase = true)
}

data class PositionInfo(
    val trackDuration: String = "",
    val relativeTime: String = "",
    val currentTrackUri: String = "",
) {
    val durationSeconds: Long get() = parseHms(trackDuration)
    val positionSeconds: Long get() = parseHms(relativeTime)

    companion object {
        fun parseHms(value: String): Long {
            val parts = value.trim().split(':')
            if (parts.isEmpty()) return 0
            return try {
                when (parts.size) {
                    1 -> parts[0].toDouble().toLong()
                    2 -> (parts[0].toLong() * 60 + parts[1].toDouble().toLong())
                    3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 +
                        parts[2].toDouble().toLong()
                    else -> 0L
                }
            } catch (_: NumberFormatException) {
                0L
            }
        }

        fun format(seconds: Long): String {
            if (seconds <= 0) return "0:00:00"
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
