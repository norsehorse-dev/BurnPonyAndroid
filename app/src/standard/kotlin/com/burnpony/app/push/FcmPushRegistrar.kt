//
// FcmPushRegistrar.kt (standard flavor)
// Registers pending receipt notes (receiptEnabled && !pushRegistered &&
// !goneFromServer) against POST /notes/{id}/push with the FCM body shape.
// Failures are silent; the repository retries during every Sent Notes
// refresh, matching the iOS PushManager behavior.
//

package com.burnpony.app.push

import com.burnpony.app.data.api.BurnPonyApi
import com.burnpony.app.data.api.BurnPonyApiException
import com.burnpony.app.data.db.SentNoteEntity
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class FcmPushRegistrar(
    private val api: BurnPonyApi,
) : PushRegistrar {

    override suspend fun registerPendingNotes(notes: List<SentNoteEntity>): List<String> {
        if (notes.isEmpty()) return emptyList()
        val token = fetchToken() ?: return emptyList()
        val registered = mutableListOf<String>()
        for (note in notes) {
            try {
                api.registerFcmPush(
                    baseUrl = note.serverBaseUrl,
                    id = note.id,
                    managementToken = note.managementToken,
                    fcmToken = token,
                )
                registered.add(note.id)
            } catch (e: BurnPonyApiException.NotFound) {
                // Note is gone from the server; the refresh path marks it.
            } catch (e: BurnPonyApiException) {
                // Offline or transient: retried on the next refresh.
            }
        }
        return registered
    }

    private suspend fun fetchToken(): String? =
        suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
}
