//
// LegalScreen.kt
// In-app Privacy Policy and Terms of Use (tester feedback round 2, item 4).
//
// The text is bundled, not fetched. A privacy policy that exists only at the
// end of a web link is unreadable on a plane, unreadable behind a captive
// portal, and — for an app whose whole pitch is that it does not need the
// network to know anything about you — slightly absurd. The canonical copies
// still live at burnpony.app/privacy.php and /terms.php, and each document
// links out to its web version at the bottom.
//
// The push-notification paragraph is a FLAVOR-SPECIFIC string: the standard
// (Play) build carries read receipts over Firebase Cloud Messaging, and the
// foss (F-Droid) build has no Google dependency in the graph at all and no
// push path whatsoever. Saying "we use FCM" in an F-Droid build would be a
// false statement in a privacy policy, which is the one document where that
// is least acceptable. See app/src/{standard,foss}/res/values/.
//
// Keep this text in sync with the website and with the iOS LegalView.swift.
// All three are the same document.
//

package com.burnpony.app.ui.legal

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.core.net.toUri
import com.burnpony.app.R
import com.burnpony.app.theme.BurnPonyTheme

/** One heading + body pair. A null heading renders as an un-headed lede. */
data class LegalSection(
    @StringRes val headingResId: Int?,
    @StringRes val bodyResId: Int,
)

enum class LegalDocument(
    val route: String,
    @StringRes val titleResId: Int,
    val webUrl: String,
    val sections: List<LegalSection>,
) {
    PRIVACY(
        route = "privacy",
        titleResId = R.string.legal_privacy_title,
        webUrl = "https://burnpony.app/privacy.php",
        sections = listOf(
            LegalSection(null, R.string.privacy_intro),
            LegalSection(R.string.privacy_who_h, R.string.privacy_who_b),
            LegalSection(R.string.privacy_app_h, R.string.privacy_app_b),
            LegalSection(R.string.privacy_relay_h, R.string.privacy_relay_b),
            LegalSection(R.string.privacy_deletion_h, R.string.privacy_deletion_b),
            LegalSection(R.string.privacy_ratelimit_h, R.string.privacy_ratelimit_b),
            LegalSection(R.string.privacy_abuse_h, R.string.privacy_abuse_b),
            LegalSection(R.string.privacy_push_h, R.string.privacy_push_b),
            LegalSection(R.string.privacy_third_h, R.string.privacy_third_b),
            LegalSection(R.string.privacy_retention_h, R.string.privacy_retention_b),
            LegalSection(R.string.privacy_children_h, R.string.privacy_children_b),
            LegalSection(R.string.privacy_changes_h, R.string.privacy_changes_b),
            LegalSection(R.string.privacy_contact_h, R.string.privacy_contact_b),
        ),
    ),
    TERMS(
        route = "terms",
        titleResId = R.string.legal_terms_title,
        webUrl = "https://burnpony.app/terms.php",
        sections = listOf(
            LegalSection(R.string.terms_agreement_h, R.string.terms_agreement_b),
            LegalSection(R.string.terms_service_h, R.string.terms_service_b),
            LegalSection(R.string.terms_use_h, R.string.terms_use_b),
            LegalSection(R.string.terms_link_h, R.string.terms_link_b),
            LegalSection(R.string.terms_ephemeral_h, R.string.terms_ephemeral_b),
            LegalSection(R.string.terms_warranty_h, R.string.terms_warranty_b),
            LegalSection(R.string.terms_relay_h, R.string.terms_relay_b),
            LegalSection(R.string.terms_changes_h, R.string.terms_changes_b),
            LegalSection(R.string.terms_contact_h, R.string.terms_contact_b),
        ),
    );

    companion object {
        /** Unknown route falls back to the privacy policy rather than crashing. */
        fun fromRoute(route: String?): LegalDocument =
            entries.firstOrNull { it.route == route } ?: PRIVACY
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(document: LegalDocument, onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        containerColor = BurnPonyTheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(document.titleResId)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.legal_back),
                            tint = BurnPonyTheme.ember,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BurnPonyTheme.background,
                    titleContentColor = BurnPonyTheme.ink,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                stringResource(R.string.legal_last_updated),
                style = MaterialTheme.typography.bodySmall,
                color = BurnPonyTheme.dim,
            )

            for (section in document.sections) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BurnPonyTheme.panel, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (section.headingResId != null) {
                        Text(
                            stringResource(section.headingResId),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = BurnPonyTheme.ink,
                        )
                    }
                    Text(
                        stringResource(section.bodyResId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (section.headingResId == null) {
                            BurnPonyTheme.ink
                        } else {
                            BurnPonyTheme.dim
                        },
                    )
                }
            }

            Text(
                stringResource(R.string.legal_view_online),
                style = MaterialTheme.typography.bodyMedium,
                color = BurnPonyTheme.ember,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, document.webUrl.toUri())
                            )
                        }
                    }
                    .padding(vertical = 8.dp),
            )
        }
    }
}
