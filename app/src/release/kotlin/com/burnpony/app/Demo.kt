//
// Demo.kt (release source set)
// No-op stub with the same public API as the debug Demo used for store
// screenshots. Release builds (Play and F-Droid) carry none of the demo
// code or data; with active fixed to false R8 drops the call sites' demo
// branches.
//

package com.burnpony.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.burnpony.app.ui.compose.ComposeUiState

object Demo {

    const val EXTRA_LANG = "demo_lang"
    const val EXTRA_SCREEN = "demo_screen"

    val active: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun handle(activity: AppCompatActivity, intent: Intent?): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun composeOverride(base: ComposeUiState): ComposeUiState? = null
}
