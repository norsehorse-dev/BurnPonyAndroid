//
// PushSupport.kt (foss flavor)
// Permanently push-free: no Google bits anywhere in the dependency graph.
// Read receipts arrive via the polling that already exists in the Sent Notes
// refresh path. Verify with:
//   ./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath
// (no com.google.firebase / com.google.android.gms artifacts may appear).
//

package com.burnpony.app.push

import android.content.Context
import com.burnpony.app.data.api.BurnPonyApi

object PushSupport {

    const val wantsNotificationPermission = false

    @Suppress("UNUSED_PARAMETER")
    fun createRegistrar(context: Context, api: BurnPonyApi): PushRegistrar = NoOpPushRegistrar
}
