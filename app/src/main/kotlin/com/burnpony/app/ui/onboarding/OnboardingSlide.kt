//
// OnboardingSlide.kt
// Content model for the first-run walkthrough (tester feedback round 2,
// item 1: "User Onboarding and Dynamic Walkthrough").
//
// Six slides: language, welcome, how the encryption works, the rules you
// set, a LIVE send step, and what happens after. Slide 4 is the point of the
// thing — the tour does not describe sending a note, it sends one. Someone
// who finishes onboarding has a real link and a real record in Sent Notes.
//
// Structure mirrors PGPony's OnboardingSlide.kt so the two apps stay
// recognisably siblings; the copy and the inline action differ because
// BurnPony's first meaningful action is "send", not "generate a key".
//
// Titles and bodies are @StringRes, and as of 1.2.0 the carousel is
// translated into all eight shipped locales (de, es, fr, it, ja, pt-rBR, ru,
// zh-rCN). 1.1.0 shipped it English-only. The in-app Privacy Policy and Terms
// BODIES remain English by deliberate choice - see ui/legal/LegalScreen.kt.
//

package com.burnpony.app.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.burnpony.app.R
import com.burnpony.app.theme.BurnPonyTheme

data class OnboardingSlide(
    val icon: ImageVector,
    val iconTint: Color,
    @StringRes val titleResId: Int,
    @StringRes val bodyResId: Int,
    /** Slide 3 only: renders the CTA that opens the live send sheet. */
    val showSendCta: Boolean = false,
    /** Slide 5 only: the honest-limits panel, in the Settings voice. */
    @StringRes val footnoteResId: Int? = null,
    /** Slide 0 only: the inline language picker. */
    val showLanguagePicker: Boolean = false,
)

object OnboardingSlides {

    val all: List<OnboardingSlide> = listOf(

        // -- Slide 0 - Language (1.2.0) ----------------------------------
        //
        // Language comes FIRST, before any other onboarding copy, matching
        // PGPony. The device language is already applied at this point
        // (BurnPonyApp.onCreate mirrors it), so a German speaker sees German
        // slide copy from the first frame and simply taps Next. The picker is
        // for the case Android cannot guess: a phone set to a language
        // BurnPony does not ship, or a user who wants the app in a different
        // language from the rest of the device.
        //
        // Title and body are deliberately short - the nine-row picker needs
        // the vertical room.
        OnboardingSlide(
            icon = Icons.Filled.Language,
            iconTint = BurnPonyTheme.ember,
            titleResId = R.string.onboarding_language_title,
            bodyResId = R.string.onboarding_language_body,
            showLanguagePicker = true,
        ),

        // -- Slide 1 - Welcome -------------------------------------------
        OnboardingSlide(
            icon = Icons.Filled.LocalFireDepartment,
            iconTint = BurnPonyTheme.ember,
            titleResId = R.string.onboarding_welcome_title,
            bodyResId = R.string.onboarding_welcome_body,
        ),

        // -- Slide 2 - How it works --------------------------------------
        OnboardingSlide(
            icon = Icons.Filled.Lock,
            iconTint = BurnPonyTheme.ember,
            titleResId = R.string.onboarding_crypto_title,
            bodyResId = R.string.onboarding_crypto_body,
        ),

        // -- Slide 3 - The rules you set ---------------------------------
        OnboardingSlide(
            icon = Icons.Filled.Tune,
            iconTint = BurnPonyTheme.ember,
            titleResId = R.string.onboarding_rules_title,
            bodyResId = R.string.onboarding_rules_body,
        ),

        // -- Slide 4 - Send one for real ---------------------------------
        OnboardingSlide(
            icon = Icons.Filled.Send,
            iconTint = BurnPonyTheme.activeGreen,
            titleResId = R.string.onboarding_send_title,
            bodyResId = R.string.onboarding_send_body,
            showSendCta = true,
        ),

        // -- Slide 5 - After it is sent ----------------------------------
        OnboardingSlide(
            icon = Icons.Filled.Timer,
            iconTint = BurnPonyTheme.ember,
            titleResId = R.string.onboarding_after_title,
            bodyResId = R.string.onboarding_after_body,
            footnoteResId = R.string.onboarding_after_footnote,
        ),
    )
}
