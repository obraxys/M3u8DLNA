package com.mavis.m3u8dlna.dlna

/** Pure parser for SSDP replies — kept Android-free so it can be unit tested. */
object SsdpParser {

    /**
     * Parses an `HTTP/1.1 200 OK` M-SEARCH reply or a `NOTIFY * HTTP/1.1`
     * announcement. Header names are case-insensitive per the SSDP spec.
     */
    fun parse(raw: String, notification: Boolean = raw.trimStart().startsWith("NOTIFY")): SsdpHit? {
        val lines = raw.replace("\r\n", "\n").split('\n')
        if (lines.isEmpty()) return null

        val status = lines.first().trim()
        val isHttpOk = status.startsWith("HTTP/", ignoreCase = true) &&
            status.contains(" 200", ignoreCase = true)
        if (!notification && !isHttpOk) return null
        if (notification && !status.startsWith("NOTIFY", ignoreCase = true)) return null

        val headers = HashMap<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (name.isNotEmpty() && name !in headers) headers[name] = value
        }

        val location = headers["location"]?.takeIf { it.isNotBlank() } ?: return null
        val usn = headers["usn"]?.takeIf { it.isNotBlank() } ?: location
        val searchTarget = headers["st"] ?: headers["nt"] ?: ""
        val byebye = headers["nts"]?.equals("ssdp:byebye", ignoreCase = true) == true

        return SsdpHit(
            location = location,
            usn = usn,
            searchTarget = if (byebye) searchTarget else searchTarget,
            notification = notification,
        ).copy(searchTarget = if (byebye) "$searchTarget|byebye" else searchTarget)
    }

    /**
     * Cheap pre-filter: fetching a description document over the network for
     * every printer on the LAN is pure waste.
     */
    fun isInterestingTarget(searchTarget: String): Boolean {
        val t = searchTarget.lowercase()
        if (t.isBlank() || t == "ssdp:all") return true
        if (t.contains("mediarenderer")) return true
        val ignore = listOf(
            "printer", "mediaserver", "internetgatewaydevice", "wandevice",
            "binarylight", "wmndevice", "upnp:basictype", "urn:schemas-upnp-org:device:lights",
        )
        return ignore.none { t.contains(it) }
    }

    fun isByebye(searchTarget: String): Boolean = searchTarget.endsWith("|byebye", ignoreCase = true)
}
