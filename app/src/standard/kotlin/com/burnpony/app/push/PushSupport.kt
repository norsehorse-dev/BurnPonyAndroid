//
// PushSupport.kt (standard flavor)
// FCM read receipts. Firebase is initialized MANUALLY from the
// google-services.json values instead of applying the com.google.gms
// google-services Gradle plugin: the values below are not secrets (they ship
// inside every Android app binary), and skipping the plugin keeps the build
// graph identical between flavors except for the firebase-messaging artifact
// itself — which keeps the foss flavor trivially Google-free and the build
// auditable.
//
// Source of the values: google-services.json for project burnpony-11351,
// android client com.burnpony.app.
//

package com.burnpony.app.push

import android.content.Context
import com.burnpony.app.data.api.BurnPonyApi
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object PushSupport {

    /** POST_NOTIFICATIONS is requested at the first meaningful moment (creating a receipt note). */
    const val wantsNotificationPermission = true

    fun createRegistrar(context: Context, api: BurnPonyApi): PushRegistrar {
        ensureFirebase(context)
        return FcmPushRegistrar(api)
    }

    fun ensureFirebase(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId("burnpony-11351")
                    .setApplicationId("1:3513183711:android:173d1842d6e3c477cee86a")
                    .setApiKey("AIzaSyDI8JYCQcAfTnw-TRakUlL_00Y6rAvqy-Q")
                    .setGcmSenderId("3513183711")
                    .build(),
            )
        }
    }
}
