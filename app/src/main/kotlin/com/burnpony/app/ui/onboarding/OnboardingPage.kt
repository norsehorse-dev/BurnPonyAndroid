//
// OnboardingPage.kt
// Renders a single walkthrough slide: tinted icon circle, title, body, and
// one optional inline element - the live "Send a note now" call to action
// on slide 3, or the honest-limits panel on slide 4.
//
// Scrollable rather than centred-and-clipped: the bodies are long by
// design (this app explains its own threat model rather than saying
// "military-grade"), and a 5-inch phone in a large font size must still be
// able to read to the end.
//

package com.burnpony.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.burnpony.app.R
import com.burnpony.app.theme.BurnPonyTheme
import com.burnpony.app.ui.language.LanguagePickerRows

@Composable
fun OnboardingPage(
    slide: OnboardingSlide,
    alreadySent: Boolean,
    onSendTapped: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(slide.iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                slide.icon,
                contentDescription = null,
                tint = slide.iconTint,
                modifier = Modifier.size(74.dp),
            )
        }

        Spacer(Modifier.height(36.dp))

        Text(
            stringResource(slide.titleResId),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = BurnPonyTheme.ink,
        )

        Spacer(Modifier.height(18.dp))

        Text(
            stringResource(slide.bodyResId),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = BurnPonyTheme.dim,
        )

        // Slide 0. Tapping a row recreates the Activity, which rebuilds this
        // whole carousel in the new locale - so the picker IS the slide, and
        // the title and body above it are intentionally brief.
        if (slide.showLanguagePicker) {
            Spacer(Modifier.height(28.dp))
            LanguagePickerRows(compact = true)
        }

        if (slide.showSendCta) {
            Spacer(Modifier.height(30.dp))
            if (alreadySent) SentConfirmation() else SendCta(onSendTapped)
        }

        if (slide.footnoteResId != null) {
            Spacer(Modifier.height(26.dp))
            Text(
                stringResource(slide.footnoteResId),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = BurnPonyTheme.dim,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BurnPonyTheme.panel)
                    .padding(14.dp),
            )
        }
    }
}

@Composable
private fun SendCta(onSendTapped: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BurnPonyTheme.emberGradient, RoundedCornerShape(12.dp))
                .clickable(onClick = onSendTapped)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = BurnPonyTheme.buttonInk,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.onboarding_send_cta),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BurnPonyTheme.buttonInk,
                )
            }
        }
        Text(
            stringResource(R.string.onboarding_send_cta_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = BurnPonyTheme.dim,
        )
    }
}

@Composable
private fun SentConfirmation() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BurnPonyTheme.activeGreen.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = BurnPonyTheme.activeGreen,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.onboarding_sent_confirm_title),
                style = MaterialTheme.typography.bodyLarge,
                color = BurnPonyTheme.ink,
            )
            Text(
                stringResource(R.string.onboarding_sent_confirm_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = BurnPonyTheme.dim,
            )
        }
    }
}
