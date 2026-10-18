package com.mavis.m3u8dlna.dlna

/**
 * Minimal SOAP 1.1 writer/reader for UPnP control points.
 *
 * Kept free of Android/OkHttp types so it can be unit tested on the JVM.
 */
object Soap {

    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Wraps [body] (already-escaped inner XML) into a SOAP envelope. */
    fun envelope(body: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append(body)
        append("</s:Body></s:Envelope>")
    }

    fun actionHeader(serviceType: String, action: String): String = "\"$serviceType#$action\""

    /**
     * Pulls `errorCode` / `errorDescription` out of a 500 SOAP fault, falling
     * back to the whole body. Returns null when the body has no error at all.
     */
    fun parseFault(body: String): String? {
        val code = tagValue(body, "errorCode")
        val description = tagValue(body, "errorDescription")
        return when {
            code != null && description != null -> "$code: $description"
            code != null -> code
            description != null -> description
            body.contains("Fault") -> body.take(300)
            else -> null
        }
    }

    fun tagValue(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf(close, start + open.length)
        if (end < 0) return null
        return unescape(xml.substring(start + open.length, end))
    }

    fun unescape(value: String): String {
        if ('&' !in value) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&") // must be last
    }

    /** DIDL-Lite metadata; many renderers show the title from here in their OSD. */
    fun didlMetadata(title: String, url: String): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"0\" parentID=\"0\" restricted=\"1\">")
        append("<dc:title>").append(escape(title.ifBlank { "Video" })).append("</dc:title>")
        append("<upnp:class>object.item.videoItem</upnp:class>")
        append("<res protocolInfo=\"http-get:*:video/mpeg:*\" ")
        append("size=\"0\" duration=\"0:00:00.000\">")
        append(escape(url))
        append("</res>")
        append("</item></DIDL-Lite>")
    }
}
