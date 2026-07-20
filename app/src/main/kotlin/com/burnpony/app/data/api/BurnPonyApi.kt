//
// BurnPonyApi.kt
// Thin OkHttp client for the BurnPony relay, mirroring BurnPonyAPI.swift.
// Base: {serverBaseURL}/api; each stored note remembers the server it was
// created on, so every call takes the base URL explicitly.
// Constant 404: burned, expired, never-existed, malformed and wrong-token all
// return byte-identical {"error":"not_found"} — surfaced here as NotFound.
//

package com.burnpony.app.data.api

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed class BurnPonyApiException : Exception() {
    class BadServerUrl : BurnPonyApiException()
    class RateLimited : BurnPonyApiException()
    class NoteTooLarge : BurnPonyApiException()
    class NotFound : BurnPonyApiException()
    class Server(val code: Int) : BurnPonyApiException()
    class Network(override val cause: Throwable) : BurnPonyApiException()
    class BadResponse : BurnPonyApiException()
}

class BurnPonyApi(
    private val client: OkHttpClient,
    private val moshi: Moshi,
) {

    companion object {
        const val DEFAULT_BASE_URL = "https://burnpony.app"
        private const val TOKEN_HEADER = "X-Management-Token"
        private val JSON = "application/json".toMediaType()
    }

    private fun endpoint(baseUrl: String, path: String): okhttp3.HttpUrl {
        val trimmed = baseUrl.trimEnd('/')
        val url = ("$trimmed/api$path").toHttpUrlOrNull()
            ?: throw BurnPonyApiException.BadServerUrl()
        if (url.scheme != "https" && url.scheme != "http") {
            throw BurnPonyApiException.BadServerUrl()
        }
        return url
    }

    private suspend fun perform(request: Request): Pair<ByteArray, Int> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    Pair(response.body?.bytes() ?: ByteArray(0), response.code)
                }
            } catch (e: IOException) {
                throw BurnPonyApiException.Network(e)
            }
        }

    /**
     * POST /notes. [envelopeJson] is the deterministic envelope string from
     * the crypto core; it travels inside the request as the opaque
     * "ciphertext" field, exactly like the iOS client.
     */
    suspend fun createNote(
        baseUrl: String,
        envelopeJson: String,
        viewsAllowed: Int,
        expiresIn: Int,
        receiptEnabled: Boolean,
    ): CreateNoteResponse {
        val bodyAdapter = moshi.adapter(CreateNoteRequest::class.java)
        val body = bodyAdapter.toJson(
            CreateNoteRequest(
                ciphertext = envelopeJson,
                viewsAllowed = viewsAllowed,
                expiresIn = expiresIn,
                receiptEnabled = receiptEnabled,
            )
        )
        val request = Request.Builder()
            .url(endpoint(baseUrl, "/notes"))
            .post(body.toRequestBody(JSON))
            .build()
        val (data, status) = perform(request)
        return when (status) {
            201 -> parse(data, CreateNoteResponse::class.java)
            413 -> throw BurnPonyApiException.NoteTooLarge()
            429 -> throw BurnPonyApiException.RateLimited()
            else -> throw BurnPonyApiException.Server(status)
        }
    }

    /** GET /notes/{id}/status with the management token header. */
    suspend fun noteStatus(
        baseUrl: String,
        id: String,
        managementToken: String,
    ): NoteStatusResponse {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "/notes/$id/status"))
            .header(TOKEN_HEADER, managementToken)
            .get()
            .build()
        val (data, status) = perform(request)
        return when (status) {
            200 -> parse(data, NoteStatusResponse::class.java)
            404 -> throw BurnPonyApiException.NotFound()
            else -> throw BurnPonyApiException.Server(status)
        }
    }

    /** DELETE /notes/{id}: early burn. */
    suspend fun burnNote(baseUrl: String, id: String, managementToken: String) {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "/notes/$id"))
            .header(TOKEN_HEADER, managementToken)
            .delete()
            .build()
        val (_, status) = perform(request)
        when (status) {
            200 -> return
            404 -> throw BurnPonyApiException.NotFound()
            else -> throw BurnPonyApiException.Server(status)
        }
    }

    /**
     * POST /notes/{id}/push with the FCM body shape (standard flavor).
     * The legacy APNs shape on the same endpoint belongs to the iOS app.
     */
    suspend fun registerFcmPush(
        baseUrl: String,
        id: String,
        managementToken: String,
        fcmToken: String,
    ) {
        val body = moshi.adapter(FcmPushRegisterRequest::class.java)
            .toJson(FcmPushRegisterRequest(token = fcmToken))
        val request = Request.Builder()
            .url(endpoint(baseUrl, "/notes/$id/push"))
            .header(TOKEN_HEADER, managementToken)
            .post(body.toRequestBody(JSON))
            .build()
        val (_, status) = perform(request)
        when (status) {
            200 -> return
            404 -> throw BurnPonyApiException.NotFound()
            else -> throw BurnPonyApiException.Server(status)
        }
    }

    private fun <T> parse(data: ByteArray, type: Class<T>): T {
        return try {
            moshi.adapter(type).fromJson(String(data, Charsets.UTF_8))
                ?: throw BurnPonyApiException.BadResponse()
        } catch (e: BurnPonyApiException) {
            throw e
        } catch (e: Exception) {
            throw BurnPonyApiException.BadResponse()
        }
    }
}
