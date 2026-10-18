package com.mavis.m3u8dlna.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * UPnP AVTransport control point: everything you can do to a renderer once its
 * control URL is known. Also covers RenderingControl, because being able to set
 * the TV's volume from the phone is the single most useful extra button here.
 */
class AvTransportClient(
    private val client: OkHttpClient = defaultClient(),
    private val controlUrl: String,
    private val renderingControlUrl: String? = null,
) {

    suspend fun setUri(uri: String, title: String): SoapResult = call(Soap.AV_TRANSPORT, "SetAVTransportURI") { body ->
        body.append("<u:SetAVTransportURI xmlns:u=\"${Soap.AV_TRANSPORT}\">")
        body.append("<InstanceID>0</InstanceID>")
        body.append("<CurrentURI>${Soap.escape(uri)}</CurrentURI>")
        body.append("<CurrentURIMetaData>${Soap.escape(Soap.didlMetadata(title, uri))}</CurrentURIMetaData>")
        body.append("</u:SetAVTransportURI>")
    }

    suspend fun play(): SoapResult = call(Soap.AV_TRANSPORT, "Play") { body ->
        body.append("<u:Play xmlns:u=\"${Soap.AV_TRANSPORT}\">")
        body.append("<InstanceID>0</InstanceID>")
        body.append("<Speed>1</Speed>")
        body.append("</u:Play>")
    }

    suspend fun pause(): SoapResult = call(Soap.AV_TRANSPORT, "Pause") { body ->
        body.append("<u:Pause xmlns:u=\"${Soap.AV_TRANSPORT}\">")
        body.append("<InstanceID>0</InstanceID>")
        body.append("</u:Pause>")
    }

    suspend fun stop(): SoapResult = call(Soap.AV_TRANSPORT, "Stop") { body ->
        body.append("<u:Stop xmlns:u=\"${Soap.AV_TRANSPORT}\">")
        body.append("<InstanceID>0</InstanceID>")
        body.append("</u:Stop>")
    }

    suspend fun transportInfo(): TransportInfo? {
        val result = call(Soap.AV_TRANSPORT, "GetTransportInfo") { body ->
            body.append("<u:GetTransportInfo xmlns:u=\"${Soap.AV_TRANSPORT}\">")
            body.append("<InstanceID>0</InstanceID>")
            body.append("</u:GetTransportInfo>")
        }
        val success = result.successOrNull ?: return null
        val state = Soap.tagValue(success.body, "TransportState") ?: return null
        return TransportInfo(state, Soap.tagValue(success.body, "TransportStatus").orEmpty())
    }

    suspend fun positionInfo(): PositionInfo? {
        val result = call(Soap.AV_TRANSPORT, "GetPositionInfo") { body ->
            body.append("<u:GetPositionInfo xmlns:u=\"${Soap.AV_TRANSPORT}\">")
            body.append("<InstanceID>0</InstanceID>")
            body.append("</u:GetPositionInfo>")
        }
        val success = result.successOrNull ?: return null
        return PositionInfo(
            trackDuration = Soap.tagValue(success.body, "TrackDuration").orEmpty(),
            relativeTime = Soap.tagValue(success.body, "RelTime").orEmpty(),
            currentTrackUri = Soap.tagValue(success.body, "CurrentTrackURI").orEmpty(),
        )
    }

    suspend fun getVolume(): Int? {
        val url = renderingControlUrl ?: return null
        val result = call(Soap.RENDERING_CONTROL, "GetVolume", url) { body ->
            body.append("<u:GetVolume xmlns:u=\"${Soap.RENDERING_CONTROL}\">")
            body.append("<InstanceID>0</InstanceID>")
            body.append("<Channel>Master</Channel>")
            body.append("</u:GetVolume>")
        }
        return Soap.tagValue(result.successOrNull?.body.orEmpty(), "CurrentVolume")?.toIntOrNull()
    }

    suspend fun setVolume(volume: Int): SoapResult {
        val url = renderingControlUrl ?: return SoapResult.Failure(0, "Renderer has no RenderingControl")
        val clamped = volume.coerceIn(0, 100)
        return call(Soap.RENDERING_CONTROL, "SetVolume", url) { body ->
            body.append("<u:SetVolume xmlns:u=\"${Soap.RENDERING_CONTROL}\">")
            body.append("<InstanceID>0</InstanceID>")
            body.append("<DesiredVolume>$clamped</DesiredVolume>")
            body.append("<Channel>Master</Channel>")
            body.append("</u:SetVolume>")
        }
    }

    private suspend fun call(
        serviceType: String,
        action: String,
        url: String = controlUrl,
        build: (StringBuilder) -> Unit,
    ): SoapResult = withContext(Dispatchers.IO) {
        val body = StringBuilder().also(build).toString()
        val request = Request.Builder()
            .url(url)
            .header("SOAPAction", Soap.actionHeader(serviceType, action))
            .header("Content-Type", """text/xml; charset="utf-8"""")
            .post(Soap.envelope(body).toRequestBody(XML_MEDIA_TYPE))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                if (response.isSuccessful && !text.contains("Fault")) {
                    SoapResult.Success(text)
                } else {
                    val detail = Soap.parseFault(text) ?: text.take(200).ifBlank { "empty response" }
                    SoapResult.Failure(response.code, detail)
                }
            }
        }.getOrElse { error ->
            SoapResult.Failure(0, error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        private val XML_MEDIA_TYPE = """text/xml; charset="utf-8"""".toMediaType()

        /**
         * Control calls must fail fast — a busy renderer that never answers
         * should not freeze the polling loop.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
