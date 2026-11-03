//
// BurnPonyApp.kt
// Application + hand-wired object graph (manual DI: four screens do not need
// a framework, and it keeps the dependency list short and auditable).
//

package com.burnpony.app

import android.app.Application
import android.content.Context
import com.burnpony.app.data.KeystoreSavedCopyVault
import com.burnpony.app.data.NoteRepository
import com.burnpony.app.data.SettingsStore
import com.burnpony.app.data.api.BurnPonyApi
import com.burnpony.app.data.api.HttpClientFactory
import com.burnpony.app.data.db.BurnPonyDatabase
import com.burnpony.app.i18n.LanguageState
import com.burnpony.app.push.PushSupport
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application) {

    val appContext: Context = app.applicationContext

    val settings = SettingsStore(app)

    // Codegen adapters only; no reflective factory anywhere (house rule).
    private val moshi: Moshi = Moshi.Builder().build()

    // internal (not private) so the debug-only Demo can seed screenshot rows
    // through the same Room instance the UI observes.
    internal val database = BurnPonyDatabase.build(app)

    // The relay client is supplied per request by HttpClientFactory so the
    // SOCKS/Tor proxy chosen in Settings applies uniformly and takes effect
    // immediately (the factory rebuilds only when the proxy config changes).
    val api = BurnPonyApi({ HttpClientFactory.client(appContext) }, moshi)

    // Flavor seam: FCM registrar in standard (initializes Firebase manually,
    // no google-services plugin), permanent no-op in foss.
    val repository = NoteRepository(
        api = api,
        dao = database.sentNoteDao(),
        settings = settings,
        pushRegistrar = PushSupport.createRegistrar(app, api),
        moshi = moshi,
        savedCopyVault = KeystoreSavedCopyVault(),
    )

    // Process-lifetime scope for small housekeeping jobs (saved-copy purge).
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class BurnPonyApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Saved copies: apply "Delete saved copies after" on every app start.
        container.appScope.launch {
            runCatching { container.repository.purgeExpiredSavedCopies() }
        }
        // Seed the language mirror. NOTE: MainActivity.onCreate re-syncs from
        // the Activity's configuration and is AUTHORITATIVE - on API < 33 the
        // Application context is never locale-overridden, so this call alone
        // reports the device language rather than the user's saved one. It
        // stays because it guarantees a sane value for anything reading
        // LanguageState before an Activity exists.
        LanguageState.initFromAppCompat(this)
    }
}
