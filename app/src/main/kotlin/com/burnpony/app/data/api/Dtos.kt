//
// Dtos.kt
// Wire DTOs for the BurnPony relay. Field names are the live server contract
// (section 4 of the planning doc); do not change from the client side.
// House rule: Moshi codegen via KSP only, every DTO
// @JsonClass(generateAdapter = true), plain Boolean for JSON booleans.
//

package com.burnpony.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CreateNoteRequest(
    @Json(name = "ciphertext") val ciphertext: String,
    @Json(name = "views_allowed") val viewsAllowed: Int,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "receipt_enabled") val receiptEnabled: Boolean,
)

@JsonClass(generateAdapter = true)
data class CreateNoteResponse(
    @Json(name = "id") val id: String,
    @Json(name = "management_token") val managementToken: String,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = true)
data class NoteStatusResponse(
    @Json(name = "views_allowed") val viewsAllowed: Int,
    @Json(name = "views_used") val viewsUsed: Int,
    @Json(name = "receipt_enabled") val receiptEnabled: Boolean,
    @Json(name = "burned") val burned: Boolean,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "expires_at") val expiresAt: String,
    @Json(name = "burned_at") val burnedAt: String?,
    @Json(name = "receipts") val receipts: List<String>,
)

/**
 * FCM registration body for POST /notes/{id}/push. The same endpoint also
 * accepts the legacy iOS shape {"apns_token","sandbox"}; the "platform"
 * discriminator selects the FCM path. Combined cap of 5 registrations per
 * note across both platforms, enforced server-side.
 */
@JsonClass(generateAdapter = true)
data class FcmPushRegisterRequest(
    @Json(name = "platform") val platform: String = "fcm",
    @Json(name = "token") val token: String,
)
