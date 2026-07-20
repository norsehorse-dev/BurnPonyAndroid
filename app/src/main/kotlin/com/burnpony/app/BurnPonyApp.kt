//
// BurnPonyApp.kt
// Application + hand-wired object graph (manual DI: four screens do not need
// a framework, and it keeps the dependency list short and auditable).
//

package com.burnpony.app

import android.app.Application
import com.burnpony.app.data.NoteRepository
import com.burnpony.app.data.SettingsStore
import com.burnpony.app.data.api.BurnPonyApi
import com.burnpony.app.data.db.BurnPonyDatabase
import com.burnpony.app.push.PushSupport
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {

    val settings = SettingsStore(app)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // Codegen adapters only; no reflective factory anywhere (house rule).
    private val moshi: Moshi = Moshi.Builder().build()

    private val database = BurnPonyDatabase.build(app)

    val api = BurnPonyApi(okHttpClient, moshi)

    // Flavor seam: FCM registrar in standard (initializes Firebase manually,
    // no google-services plugin), permanent no-op in foss.
    val repository = NoteRepository(
        api = api,
        dao = database.sentNoteDao(),
        settings = settings,
        pushRegistrar = PushSupport.createRegistrar(app, api),
        moshi = moshi,
    )
}

class BurnPonyApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
