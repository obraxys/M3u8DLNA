package com.mavis.m3u8dlna.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpParserTest {

    @Test
    fun `parses a search reply`() {
        val raw = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            LOCATION: http://192.168.1.20:8200/description.xml
            SERVER: Linux/3.10 UPnP/1.0 LG/1.0
            ST: urn:schemas-upnp-org:device:MediaRenderer:1
            USN: uuid:abc-123::urn:schemas-upnp-org:device:MediaRenderer:1

        """.trimIndent()

        val hit = SsdpParser.parse(raw)
        assertNotNull(hit)
        assertEquals("http://192.168.1.20:8200/description.xml", hit!!.location)
        assertEquals("uuid:abc-123", hit.udn)
        assertFalse(hit.notification)
    }

    @Test
    fun `parses a notify announcement with lower case headers`() {
        val raw = """
            NOTIFY * HTTP/1.1
            HOST: 239.255.255.250:1900
            location: http://10.0.0.5:8090/desc.xml
            nt: upnp:rootdevice
            nts: ssdp:alive
            usn: uuid:zzz::upnp:rootdevice

        """.trimIndent()

        val hit = SsdpParser.parse(raw)
        assertNotNull(hit)
        assertTrue(hit!!.notification)
        assertEquals("http://10.0.0.5:8090/desc.xml", hit.location)
        assertEquals("uuid:zzz", hit.udn)
    }

    @Test
    fun `byebye is flagged so the device can be dropped`() {
        val raw = "NOTIFY * HTTP/1.1\r\nLOCATION: http://10.0.0.5/desc.xml\r\nNT: upnp:rootdevice\r\nNTS: ssdp:byebye\r\nUSN: uuid:zzz\r\n\r\n"
        val hit = SsdpParser.parse(raw)
        assertNotNull(hit)
        assertTrue(SsdpParser.isByebye(hit!!.searchTarget))
    }

    @Test
    fun `rejects non-200 replies and requests`() {
        assertNull(SsdpParser.parse("HTTP/1.1 404 Not Found\r\nLOCATION: http://x/y\r\n\r\n"))
        assertNull(SsdpParser.parse("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n\r\n"))
    }

    @Test
    fun `rejects a payload without a location`() {
        assertNull(SsdpParser.parse("HTTP/1.1 200 OK\r\nUSN: uuid:x\r\n\r\n"))
    }

    @Test
    fun `filters out device types we cannot cast to`() {
        assertTrue(SsdpParser.isInterestingTarget("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(SsdpParser.isInterestingTarget("upnp:rootdevice"))
        assertFalse(SsdpParser.isInterestingTarget("urn:schemas-upnp-org:device:printer:1"))
        assertFalse(SsdpParser.isInterestingTarget("urn:schemas-upnp-org:device:MediaServer:1"))
    }
}

class SoapTest {

    @Test
    fun `escapes the five xml entities`() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", Soap.escape("a&b<c>d\"e'f"))
    }

    @Test
    fun `tag value is unescaped once, not twice`() {
        val xml = "<CurrentURI>http://x/a.ts?u=1&amp;v=2</CurrentURI>"
        assertEquals("http://x/a.ts?u=1&v=2", Soap.tagValue(xml, "CurrentURI"))
    }

    @Test
    fun `tag value returns null when absent`() {
        assertNull(Soap.tagValue("<a>1</a>", "CurrentURI"))
    }

    @Test
    fun `didl metadata escapes the url it embeds`() {
        val metadata = Soap.didlMetadata("Фильм & прочее", "http://10.0.0.2:8099/p/tok/index.m3u8")
        assertTrue(metadata.contains("<dc:title>Фильм &amp; прочее</dc:title>"))
        assertTrue(metadata.contains(">http://10.0.0.2:8099/p/tok/index.m3u8</res>"))
    }

    @Test
    fun `soap fault is turned into a readable message`() {
        val fault = "<s:Envelope><s:Body><s:Fault><detail>" +
            "<UPnPError><errorCode>701</errorCode>" +
            "<errorDescription>Transition not available</errorDescription></UPnPError>" +
            "</detail></s:Fault></s:Body></s:Envelope>"
        assertEquals("701: Transition not available", Soap.parseFault(fault))
    }

    @Test
    fun `envelope carries the soap namespaces`() {
        val envelope = Soap.envelope("<u:Play/>")
        assertTrue(envelope.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(envelope.contains("xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""))
        assertTrue(envelope.contains("<u:Play/>"))
    }
}

class PositionInfoTest {

    @Test
    fun `parses hms and ms timestamps`() {
        assertEquals(0L, PositionInfo.parseHms("NOT_IMPLEMENTED"))
        assertEquals(5L, PositionInfo.parseHms("0:00:05"))
        assertEquals(3725L, PositionInfo.parseHms("1:02:05"))
        assertEquals(65L, PositionInfo.parseHms("01:05.000"))
    }
}

class DeviceDescriptionParserTest {

    private val xml = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>Wrapper</friendlyName>
            <UDN>uuid:wrapper</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <controlURL>/cd/control</controlURL>
              </service>
            </serviceList>
            <deviceList>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Living Room TV</friendlyName>
                <manufacturer>LG</manufacturer>
                <modelName>OLED55</modelName>
                <UDN>uuid:renderer-1</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/ctl/AVTransport</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/ctl/RenderingControl</controlURL>
                  </service>
                </serviceList>
              </device>
            </deviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `picks the nested renderer, not the wrapper`() {
        val device = DeviceDescriptionParser.parse(
            xml = xml,
            descriptionUrl = "http://192.168.1.20:8200/description.xml",
            udnHint = "uuid:renderer-1",
            lastSeen = 0L,
        )
        assertNotNull(device)
        assertEquals("Living Room TV", device!!.friendlyName)
        assertEquals("uuid:renderer-1", device.udn)
        assertEquals("http://192.168.1.20:8200/ctl/AVTransport", device.avTransportControlUrl)
        assertEquals("http://192.168.1.20:8200/ctl/RenderingControl", device.renderingControlUrl)
        assertEquals("LG · OLED55", device.subtitle)
        assertEquals("192.168.1.20:8200", device.host)
    }

    @Test
    fun `returns null when there is no avtransport service`() {
        val noRenderer = xml.replace("MediaRenderer:1", "MediaServer:1")
            .replace("urn:schemas-upnp-org:service:AVTransport:1", "urn:schemas-upnp-org:service:X:1")
        val device = DeviceDescriptionParser.parse(
            xml = noRenderer,
            descriptionUrl = "http://192.168.1.20:8200/description.xml",
            udnHint = "uuid:x",
            lastSeen = 0L,
        )
        assertNull(device)
    }

    @Test
    fun `malformed xml yields null instead of throwing`() {
        assertNull(
            DeviceDescriptionParser.parse(
                xml = "<root><device>",
                descriptionUrl = "http://192.168.1.20/desc.xml",
                udnHint = "uuid:x",
                lastSeen = 0L,
            ),
        )
    }
}
