package com.mavis.m3u8dlna.dlna

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a UPnP device description document and picks the embedded device that
 * can actually play media.
 *
 * Wrappers (AV receivers, Android TVs, boxes) nest several `device` elements and
 * only one of them exposes AVTransport, so picking `root/device` blindly sends
 * `Play` to a control URL that does not exist.
 */
object DeviceDescriptionParser {

    private const val AV_TRANSPORT_TYPE = "urn:schemas-upnp-org:service:AVTransport:"
    private const val RENDERING_CONTROL_TYPE = "urn:schemas-upnp-org:service:RenderingControl:"
    private const val MEDIA_RENDERER = "MediaRenderer"

    fun parse(xml: String, descriptionUrl: String, udnHint: String, lastSeen: Long): DlnaDevice? {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // A description comes off the LAN: never let it pull in external DTDs.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return null

        val root = document.documentElement ?: return null
        val device = pickRendererDevice(root) ?: return null

        val avControl = controlUrlFor(device, AV_TRANSPORT_TYPE) ?: return null
        val renderControl = controlUrlFor(device, RENDERING_CONTROL_TYPE)

        val name = childText(device, "friendlyName")
            ?: childText(device, "modelName")
            ?: udnHint.removePrefix("uuid:")
            ?: "Renderer"

        return DlnaDevice(
            udn = childText(device, "UDN")?.trim() ?: udnHint,
            friendlyName = name.trim().ifBlank { "Renderer" },
            manufacturer = childText(device, "manufacturer")?.trim().orEmpty(),
            modelName = childText(device, "modelName")?.trim().orEmpty(),
            avTransportControlUrl = resolve(descriptionUrl, avControl),
            renderingControlUrl = renderControl?.let { resolve(descriptionUrl, it) },
            lastSeen = lastSeen,
        )
    }

    /** Depth-first search for the device that carries an AVTransport service. */
    private fun pickRendererDevice(root: Element): Element? {
        var fallback: Element? = null
        val queue = ArrayDeque<Element>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val element = queue.removeFirst()
            if (element.tagName.equals("device", ignoreCase = true)) {
                if (hasServiceOfType(element, AV_TRANSPORT_TYPE)) return element
                if (fallback == null &&
                    childText(element, "deviceType")?.contains(MEDIA_RENDERER) == true
                ) {
                    fallback = element
                }
            }
            childElements(element).forEach { queue.addLast(it) }
        }
        return fallback
    }

    /**
     * Services belonging to [device] itself.
     *
     * They live inside `<serviceList><service>…`, and — crucially — they must
     * not be picked up from a nested device: a wrapper (MediaServer) would
     * otherwise claim the AVTransport of the renderer inside it. So we look at
     * the device's own serviceList and never descend into a deviceList.
     */
    private fun directServices(device: Element): List<Element> {
        val result = mutableListOf<Element>()
        childElements(device).forEach { child ->
            when {
                child.tagName.equals("service", ignoreCase = true) -> result.add(child)
                child.tagName.equals("serviceList", ignoreCase = true) ->
                    childElements(child)
                        .filter { it.tagName.equals("service", ignoreCase = true) }
                        .forEach { result.add(it) }
            }
        }
        return result
    }

    private fun hasServiceOfType(device: Element, typeFragment: String): Boolean =
        directServices(device).any {
            childText(it, "serviceType").orEmpty().contains(typeFragment)
        }

    private fun controlUrlFor(device: Element, typeFragment: String): String? =
        directServices(device)
            .firstOrNull { childText(it, "serviceType").orEmpty().contains(typeFragment) }
            ?.let { childText(it, "controlURL") }
            ?.takeIf { it.isNotBlank() }

    private fun childText(element: Element, name: String): String? {
        var node: Node? = element.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName.equals(name, ignoreCase = true)) {
                return node.textContent
            }
            node = node.nextSibling
        }
        return null
    }

    private fun childElements(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        var node: Node? = element.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) (node as? Element)?.let { result.add(it) }
            node = node.nextSibling
        }
        return result
    }

    /** `controlURL` is frequently relative (`/ctl/AVTransport`). */
    private fun resolve(base: String, path: String): String = runCatching {
        URI(base).resolve(path).toString()
    }.getOrDefault(path)
}
