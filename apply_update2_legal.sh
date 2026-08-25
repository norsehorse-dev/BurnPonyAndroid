#!/usr/bin/env bash
#
# apply_update2_legal.sh
# BurnPony Android — Play update 2 of 2 (1.2.0, versionCode 4)
#
#   Tester feedback round 2, item 4: "Privacy Policy and Terms of Service
#   Implementation". Adds bundled, offline, in-app copies of both documents
#   with links from Settings, and bumps the version.
#
# RUN THIS ONLY AFTER 1.1.0 (the walkthrough release) IS BUILT AND SHIPPED.
# It is kept out of the working tree deliberately: Google Play's closed-test
# requirement wants two distinct releases, and the two changesets must not
# land in the same build.
#
# Usage, from the repository root:
#     bash apply_update2_legal.sh
#
# The script asserts on the exact text it expects to replace, so if 1.1.0 has
# not been applied — or if this has already been run — it fails loudly and
# changes nothing rather than half-applying.
#
set -euo pipefail

if [ ! -f "app/build.gradle.kts" ] || [ ! -d "app/src/main/kotlin/com/burnpony/app" ]; then
    echo "Run this from the BurnPonyAndroid repository root." >&2
    exit 1
fi

echo "==> Writing ui/legal/LegalScreen.kt"
mkdir -p app/src/main/kotlin/com/burnpony/app/ui/legal
cat > app/src/main/kotlin/com/burnpony/app/ui/legal/LegalScreen.kt <<'KOTEOF'
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
KOTEOF

echo "==> Writing res/values/strings_legal.xml"
cat > app/src/main/res/values/strings_legal.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- In-app Privacy Policy and Terms of Use (tester feedback round 2, item 4).
     Verbatim from burnpony.app/privacy.php and /terms.php, minus the two
     web-only paragraphs (site analytics, launch-notify form) which do not
     apply to the app. Kept in its own file so the reviewed 1.0 catalog in
     strings.xml stays untouched.

     NOTE FOR TRANSLATORS: privacy_push_b is NOT here. It is flavor-specific
     (standard = FCM, foss = no push at all) and lives in
     app/src/standard/res/values/ and app/src/foss/res/values/.

     House convention: apostrophes escaped as \'. -->
<resources>

    <string name="legal_privacy_title">Privacy Policy</string>
    <string name="legal_terms_title">Terms of Use</string>
    <string name="legal_last_updated">Last updated: July 7, 2026</string>
    <string name="legal_view_online">View this document on burnpony.app</string>
    <string name="legal_back">Back</string>
    <string name="settings_legal_header">Legal</string>

    <!-- Privacy Policy -->
    <string name="privacy_intro">The short version: BurnPony has no accounts, collects nothing about you, runs no analytics, and the relay cannot read your notes. Here is the long version.</string>

    <string name="privacy_who_h">Who this covers</string>
    <string name="privacy_who_b">This policy covers the BurnPony apps, the note-viewing pages at burnpony.app, and the website — all developed and operated by NorseHorse, an independent developer. Contact: NorseHorse@norsehor.se</string>

    <string name="privacy_app_h">What the app collects</string>
    <string name="privacy_app_b">Nothing about you. There is no account, no sign-up, and the app never asks for your name, email address, or contacts. The Sent tab keeps a local record of the notes you have created — the note ID, a management token, your chosen settings, the status the app has fetched back (views used, whether it burned, receipt times) and your optional device-local label — never the note text. That record exists only on this device.</string>

    <string name="privacy_relay_h">What the relay stores</string>
    <string name="privacy_relay_b">Notes are stored on a relay so recipients can fetch them. For each live note the relay holds:\n\n•  The sealed envelope — your note, encrypted on this device with AES-256-GCM. The decryption key travels only in the link\'s # fragment, which browsers do not send to servers, so the relay cannot read the note. Even the auto-hide setting travels encrypted inside it.\n\n•  Bookkeeping — a random note ID, created and expiry timestamps, the view allowance and count, whether a read receipt was requested, and a hash of the management token used for early burn.\n\n•  Receipt times — if and only if you enabled receipts for that note, the times it was opened.\n\n•  Push registrations — device push tokens (up to five per note) so your phone can receive the generic "A note was opened." notification. They are removed with the note.</string>

    <string name="privacy_deletion_h">Deletion</string>
    <string name="privacy_deletion_b">Ciphertext is deleted in the same database transaction as the last allowed view, at expiry (a sweep runs every five minutes), or when you burn the note early. Burned, expired, and never-existed notes return identical responses.</string>

    <string name="privacy_ratelimit_h">Rate limiting and network data</string>
    <string name="privacy_ratelimit_b">To keep the relay healthy, note creation and abuse reports are rate-limited using IP addresses hashed with a server-side secret; those hash records expire after roughly two hours. Like any internet server, the relay sees connecting IP addresses at the network level while handling a request; it does not build profiles from them, and the web server in front of it is configured with access logging disabled.</string>

    <string name="privacy_abuse_h">Abuse reports</string>
    <string name="privacy_abuse_b">The viewer includes a report function. A report stores the note ID, the reason text the reporter enters (up to 500 characters), and the reporting time, for operator review. Reports are purged after 30 days, and submitting one never reveals whether a note exists.</string>

    <string name="privacy_push_h">Push notifications</string>

    <string name="privacy_third_h">Third parties</string>
    <string name="privacy_third_b">There is no analytics SDK, no advertising SDK, and no crash reporter that transmits your data. Nothing is sold or shared with anyone.</string>

    <string name="privacy_retention_h">Data retention and deletion</string>
    <string name="privacy_retention_b">Note ciphertext lives exactly as long as its view allowance and expiry allow, and not a sweep-cycle longer. Receipt records and push registrations are removed with their note. Rate-limit hashes expire in about two hours; abuse reports in 30 days. Deleting the app deletes your local Sent records; because there is no account, there is no server-side profile to delete.</string>

    <string name="privacy_children_h">Children</string>
    <string name="privacy_children_b">BurnPony is not directed at children under 13 and does not knowingly collect information from anyone, children included.</string>

    <string name="privacy_changes_h">Changes</string>
    <string name="privacy_changes_b">If this policy changes, the update appears here and on the website with a new date. There is no dark-pattern re-consent flow because there is no data collection to consent to.</string>

    <string name="privacy_contact_h">Contact</string>
    <string name="privacy_contact_b">Questions about privacy: NorseHorse@norsehor.se</string>

    <!-- Terms of Use -->
    <string name="terms_agreement_h">The agreement</string>
    <string name="terms_agreement_b">By using the BurnPony app, relay, or website you accept these terms. BurnPony is operated by NorseHorse, an independent developer.</string>

    <string name="terms_service_h">The service</string>
    <string name="terms_service_b">BurnPony provides self-destructing notes: text encrypted on your device, stored temporarily as ciphertext on a relay, and delivered through a shareable link. Notes are removed once their view allowance or expiry is reached. The service is currently provided free of charge.</string>

    <string name="terms_use_h">Acceptable use</string>
    <string name="terms_use_b">Use BurnPony in accordance with the laws that apply to you. Do not use it for harassment, threats, fraud, or to distribute illegal content, and do not attempt to disrupt or overload the relay. The viewer includes an abuse reporting mechanism; reported notes may be removed, and note creation is rate-limited.</string>

    <string name="terms_link_h">Your link, your responsibility</string>
    <string name="terms_link_b">A note link — plus its passphrase, if you set one — is the sole means of reading a note. Anyone who receives the link can read the note until it burns or expires. Lost links cannot be restored, regenerated, or recovered by anyone, including the developer.</string>

    <string name="terms_ephemeral_h">Ephemerality is the product</string>
    <string name="terms_ephemeral_b">Notes are designed to be irrecoverable once they burn or expire. Do not use BurnPony as storage for anything you need to keep. Deleted ciphertext cannot be restored by anyone.</string>

    <string name="terms_warranty_h">No warranty</string>
    <string name="terms_warranty_b">BurnPony is provided "as is", without warranty of any kind, express or implied. To the maximum extent permitted by law, the developer is not liable for service interruptions, lost notes, or any damages arising from use of the service.</string>

    <string name="terms_relay_h">The relay</string>
    <string name="terms_relay_b">The relay holds encrypted notes temporarily and may be changed, moved, or taken offline to maintain the service. If you point the app at your own self-hosted relay in Settings, that relay is yours to operate and these terms do not govern it.</string>

    <string name="terms_changes_h">Changes</string>
    <string name="terms_changes_b">Updates to these terms appear here and on the website with a revised date. Continued use after a change means you accept it.</string>

    <string name="terms_contact_h">Contact</string>
    <string name="terms_contact_b">NorseHorse@norsehor.se</string>

</resources>
XMLEOF

echo "==> Writing flavor-specific push paragraph (standard = FCM, foss = none)"
mkdir -p app/src/standard/res/values app/src/foss/res/values
cat > app/src/standard/res/values/strings_legal_push.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Play build: read receipts ride Firebase Cloud Messaging. -->
<resources>
    <string name="privacy_push_b">Read receipts use Firebase Cloud Messaging to wake the app. The notification is deliberately generic — "A note was opened." with only the note ID attached — so no note content or settings transit Google\'s servers. Google\'s push infrastructure is the only third party in the system, and it carries content-free wake-up signals.</string>
</resources>
XMLEOF
cat > app/src/foss/res/values/strings_legal_push.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- F-Droid build: no Google dependency exists in this variant's graph, so
     there is no push path at all. Saying otherwise in a privacy policy would
     be a false statement in the one document where that is least acceptable. -->
<resources>
    <string name="privacy_push_b">This build contains no push messaging and no Google libraries of any kind. Read receipts are delivered by the app checking the relay when you open the Sent tab, so nothing wakes your phone and no third-party push service is involved anywhere in this version.</string>
</resources>
XMLEOF

echo "==> Writing legal chrome translations (document bodies stay English)"
mkdir -p app/src/main/res/values-de
cat > app/src/main/res/values-de/strings_legal.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Legal CHROME only, de. The Privacy Policy and Terms bodies are
     deliberately NOT translated: a machine-translated privacy policy is a
     liability. They fall back to the English in values/strings_legal.xml,
     and each document links out to burnpony.app for the canonical copy. -->
<resources>
    <string name="settings_legal_header">Rechtliches</string>
    <string name="legal_privacy_title">Datenschutzerklärung</string>
    <string name="legal_terms_title">Nutzungsbedingungen</string>
    <string name="legal_view_online">Dieses Dokument auf burnpony.app ansehen</string>
    <string name="legal_back">Zurück</string>
</resources>
XMLEOF
mkdir -p app/src/main/res/values-es
cat > app/src/main/res/values-es/strings_legal.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Legal CHROME only, es. The Privacy Policy and Terms bodies are
     deliberately NOT translated: a machine-translated privacy policy is a
     liability. They fall back to the English in values/strings_legal.xml,
     and each document links out to burnpony.app for the canonical copy. -->
<resources>
    <string name="settings_legal_header">Legal</string>
    <string name="legal_privacy_title">Política de privacidad</string>
    <string name="legal_terms_title">Condiciones de uso</string>
    <string name="legal_view_online">Ver este documento en burnpony.app</string>
    <string name="legal_back">Atrás</string>
</resources>
XMLEOF
mkdir -p app/src/main/res/values-fr
cat > app/src/main/res/values-fr/strings_legal.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Legal CHROME only, fr. The Privacy Policy and Terms bodies are
     deliberately NOT translated: a machine-translated privacy policy is a
     liability. They fall back to the English in values/strings_legal.xml,
     and each document links out to burnpony.app for the canonical copy. -->
<resources>
    <string name="settings_legal_header">Mentions légales</string>
    <string name="legal_privacy_title">Politique de confidentialité</string>
    <string name="legal_terms_title">Conditions d\'utilisation</string>
    <string name="legal_view_online">Consulter ce document sur burnpony.app</string>
    <string name="legal_back">Retour</string>
</resources>
XMLEOF
mkdir -p app/src/main/res/values-it
cat > app/src/main/res/values-it/strings_legal.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Legal CHROME only, it. The Privacy Policy and Terms bodies are
     deliberately NOT translated: a machine-translated privacy policy is a
     liability. They fall back to the English in values/strings_legal.xml,
     and each document links out to burnpony.app for the canonical copy. -->
<resources>
    <string name="settings_legal_header">Informazioni legali</string>
    <string name="legal_privacy_title">Informativa sulla privacy</string>
    <string name="legal_terms_title">Condizioni d\'uso</string>
    <string name="legal_view_online">Vedi questo documento su burnpony.app</string>
    <string name="legal_back">Indietro</string>
</resources>
XMLEOF
mkdir -p app/src/main/res/values-ja
cat > app/src/main/res/values-ja/strings_legal.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Legal CHROME only, ja. The Privacy Policy and Terms bodies are
     deliberately NOT translated: a machine-translated privacy policy is a
     liability. They fall back to the English in values/strings_legal.xml,
     and each document links out to burnpony.app for the canonical copy. -->
<resources>
    <string name="settings_legal_header">法的情報</string>
    <string name="legal_privacy_title">プライバシーポリシー</string>
    <string name="legal_terms_title">利用規約</string>
    <string name="legal_view_online">この文書を burnpony.app で見る</string>
    <string name="legal_back">戻る</string>
</resources>
XMLEOF
mkdir -p app/src/main/res/values-pt-rBR
cat > app/src/main/res/values-pt-rBR/strings_legal.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Legal CHROME only, pt-BR. The Privacy Policy and Terms bodies are
     deliberately NOT translated: a machine-translated privacy policy is a
     liability. They fall back to the English in values/strings_legal.xml,
     and each document links out to burnpony.app for the canonical copy. -->
<resources>
    <string name="settings_legal_header">Jurídico</string>
    <string name="legal_privacy_title">Política de Privacidade</string>
    <string name="legal_terms_title">Termos de Uso</string>
    <string name="legal_view_online">Ver este documento em burnpony.app</string>
    <string name="legal_back">Voltar</string>
</resources>
XMLEOF
mkdir -p app/src/main/res/values-ru
cat > app/src/main/res/values-ru/strings_legal.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Legal CHROME only, ru. The Privacy Policy and Terms bodies are
     deliberately NOT translated: a machine-translated privacy policy is a
     liability. They fall back to the English in values/strings_legal.xml,
     and each document links out to burnpony.app for the canonical copy. -->
<resources>
    <string name="settings_legal_header">Правовые документы</string>
    <string name="legal_privacy_title">Политика конфиденциальности</string>
    <string name="legal_terms_title">Условия использования</string>
    <string name="legal_view_online">Открыть этот документ на burnpony.app</string>
    <string name="legal_back">Назад</string>
</resources>
XMLEOF
mkdir -p app/src/main/res/values-zh-rCN
cat > app/src/main/res/values-zh-rCN/strings_legal.xml <<'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Legal CHROME only, zh-Hans. The Privacy Policy and Terms bodies are
     deliberately NOT translated: a machine-translated privacy policy is a
     liability. They fall back to the English in values/strings_legal.xml,
     and each document links out to burnpony.app for the canonical copy. -->
<resources>
    <string name="settings_legal_header">法律</string>
    <string name="legal_privacy_title">隐私政策</string>
    <string name="legal_terms_title">使用条款</string>
    <string name="legal_view_online">在 burnpony.app 上查看本文档</string>
    <string name="legal_back">返回</string>
</resources>
XMLEOF

echo "==> Writing fastlane changelog 4.txt"
mkdir -p fastlane/metadata/android/en-US/changelogs
cat > fastlane/metadata/android/en-US/changelogs/4.txt <<'EOF'
The Privacy Policy and Terms of Use now live inside the app.

- Both documents are bundled and readable offline, with no network request
  needed to see what we do and do not store
- Linked from Settings, and each one links out to its web copy
- The F-Droid build carries its own accurate text: that variant has no push
  messaging and no Google libraries at all
EOF

echo "==> Patching MainActivity.kt, SettingsScreen.kt and app/build.gradle.kts"
python3 - <<'PYEOF'
import io

def edit(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        if s.count(old) != 1:
            raise SystemExit(
                "ABORT: expected exactly one match in %s for:\n---\n%s\n---\nfound %d.\n"
                "Has 1.1.0 been applied, or has this script already run?"
                % (path, old[:200], s.count(old))
            )
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8').write(s)
    print("    patched", path)

B = "app/src/main/kotlin/com/burnpony/app"

edit(B + "/MainActivity.kt", [
("""import com.burnpony.app.ui.compose.ComposeViewModel""",
 """import com.burnpony.app.ui.compose.ComposeViewModel
import com.burnpony.app.ui.legal.LegalDocument
import com.burnpony.app.ui.legal.LegalScreen"""),
("""            composable("settings") {
                SettingsScreen(viewModel(factory = factory))
            }""",
 """            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel(factory = factory),
                    onOpenLegal = { document ->
                        navController.navigate("legal/" + document.route)
                    },
                )
            }
            // Legal documents are pushed onto the Settings tab rather than
            // opened as a dialog: they are long, they need their own scroll
            // position, and system back should return to Settings, not close
            // the app.
            composable("legal/{doc}") { entry ->
                LegalScreen(
                    document = LegalDocument.fromRoute(entry.arguments?.getString("doc")),
                    onBack = { navController.popBackStack() },
                )
            }"""),
])

edit(B + "/ui/settings/SettingsScreen.kt", [
("""import com.burnpony.app.data.api.BurnPonyApi""",
 """import com.burnpony.app.data.api.BurnPonyApi
import com.burnpony.app.ui.legal.LegalDocument"""),
("""fun SettingsScreen(viewModel: SettingsViewModel) {""",
 """fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenLegal: (LegalDocument) -> Unit,
) {"""),
("""            // Version + family footer""",
 """            // Legal (tester feedback round 2, item 4). Bundled and offline;
            // each document also links out to its canonical web copy.
            SectionPanel(header = stringResource(R.string.settings_legal_header)) {
                Text(
                    stringResource(R.string.legal_privacy_title),
                    color = BurnPonyTheme.ember,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLegal(LegalDocument.PRIVACY) }
                        .padding(vertical = 6.dp),
                )
                Text(
                    stringResource(R.string.legal_terms_title),
                    color = BurnPonyTheme.ember,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLegal(LegalDocument.TERMS) }
                        .padding(vertical = 6.dp),
                )
            }

            // Version + family footer"""),
])

edit("app/build.gradle.kts", [
('        versionCode = 3\n        versionName = "1.1.0"',
 '        versionCode = 4\n        versionName = "1.2.0"'),
])
PYEOF

echo
echo "==> Done. BurnPony Android is now at 1.2.0 (versionCode 4)."
echo "    New:      app/src/main/kotlin/com/burnpony/app/ui/legal/LegalScreen.kt"
echo "              app/src/main/res/values/strings_legal.xml"
echo "              app/src/{standard,foss}/res/values/strings_legal_push.xml"
echo "    Modified: MainActivity.kt, ui/settings/SettingsScreen.kt, app/build.gradle.kts"
echo
echo "    Build both flavors before shipping — the foss variant is the one"
echo "    whose privacy text differs, and it is the easy one to forget:"
echo "        ./gradlew assembleStandardRelease assembleFossRelease"
