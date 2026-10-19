package io.github.ddagunts.screencast.mediapush

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI

data class DlnaDevice(
    val name: String,
    val host: String,
    val controlUrl: String,
    val serviceType: String,
)

internal object DlnaProtocol {
    const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:"

    fun searchRequest(): ByteArray = ("M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $SEARCH_TARGET\r\n\r\n")
        .toByteArray(Charsets.US_ASCII)

    fun location(response: String): String? {
        if (!response.startsWith("HTTP/1.1 200", ignoreCase = true) &&
            !response.startsWith("HTTP/1.0 200", ignoreCase = true)) return null
        return response.lineSequence().firstNotNullOfOrNull { line ->
            if (line.substringBefore(':').trim().equals("location", true))
                line.substringAfter(':').trim().takeIf { it.startsWith("http://", true) }
            else null
        }
    }

    fun parseDescription(xml: String, location: String): DlnaDevice? {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        var name = ""
        var urlBase = ""
        var serviceType = ""
        var controlPath = ""
        var chosenType = ""
        var chosenPath = ""
        var inService = false
        var tag = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name.substringAfter(':')
                    if (tag == "service") {
                        inService = true
                        serviceType = ""
                        controlPath = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    val value = parser.text.trim()
                    if (value.isNotEmpty()) when (tag) {
                        "friendlyName" -> if (name.isEmpty()) name = value
                        "URLBase" -> urlBase = value
                        "serviceType" -> if (inService) serviceType = value
                        "controlURL" -> if (inService) controlPath = value
                    }
                }
                XmlPullParser.END_TAG -> {
                    val ending = parser.name.substringAfter(':')
                    if (ending == "service") {
                        if (chosenType.isEmpty() && serviceType.startsWith(AV_TRANSPORT) &&
                            controlPath.isNotBlank()) {
                            chosenType = serviceType
                            chosenPath = controlPath
                        }
                        inService = false
                    }
                    tag = ""
                }
            }
            parser.next()
        }
        if (chosenType.isEmpty()) return null
        val base = URI(urlBase.ifBlank { location })
        val control = base.resolve(chosenPath)
        require(control.scheme.equals("http", true)) { "DLNA control endpoint must use HTTP" }
        return DlnaDevice(name.ifBlank { control.host }, control.host,
            control.toString(), chosenType)
    }

    fun didl(item: MediaItem, mediaUrl: String): String {
        val mediaClass = when {
            item.mimeType.startsWith("image/") -> "object.item.imageItem.photo"
            item.mimeType.startsWith("audio/") -> "object.item.audioItem.musicTrack"
            else -> "object.item.videoItem"
        }
        val size = if (item.local && item.size >= 0) " size=\"${item.size}\"" else ""
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${escape(item.title)}</dc:title>" +
            "<upnp:class>$mediaClass</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${escape(item.mimeType)}:*\"$size>" +
            "${escape(mediaUrl)}</res></item></DIDL-Lite>"
    }

    fun envelope(serviceType: String, action: String, args: Map<String, String>): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${escape(serviceType)}\">" +
            args.entries.joinToString("") { (key, value) -> "<$key>${escape(value)}</$key>" } +
            "</u:$action></s:Body></s:Envelope>"

    fun transportState(xml: String): String? {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        var inside = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> inside = parser.name.substringAfter(':') == "CurrentTransportState"
                XmlPullParser.TEXT -> if (inside) return parser.text.trim()
                XmlPullParser.END_TAG -> inside = false
            }
            parser.next()
        }
        return null
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) append(when (c) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&apos;"
            else -> c.toString()
        })
    }
}
