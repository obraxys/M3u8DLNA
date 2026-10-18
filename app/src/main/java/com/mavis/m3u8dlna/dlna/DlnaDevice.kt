package com.mavis.m3u8dlna.dlna

/** A device that answered SSDP, before its description XML has been read. */
data class SsdpHit(
    val location: String,
    val usn: String,
    val searchTarget: String,
    val notification: Boolean = false,
) {
    /** `uuid:xxxx` part of a USN like `uuid:xxxx::urn:schemas-upnp-org:device:MediaRenderer:1`. */
    val udn: String get() = usn.substringBefore("::")
}

/** Everything we learned about a renderer from its device description. */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String = "",
    val modelName: String = "",
    val avTransportControlUrl: String,
    val renderingControlUrl: String? = null,
    val lastSeen: Long = System.currentTimeMillis(),
) {
    val subtitle: String
        get() = listOf(manufacturer, modelName).filter { it.isNotBlank() }.joinToString(" · ")

    /** `http://192.168.1.20:8200/` — handy for showing which TV this is. */
    val origin: String
        get() = runCatching {
            val withoutScheme = avTransportControlUrl.substringAfter("://")
            val host = withoutScheme.substringBefore('/')
            val scheme = avTransportControlUrl.substringBefore("://")
            "$scheme://$host/"
        }.getOrDefault("")

    val host: String
        get() = runCatching {
            avTransportControlUrl.substringAfter("://").substringBefore('/')
        }.getOrDefault("")
}
