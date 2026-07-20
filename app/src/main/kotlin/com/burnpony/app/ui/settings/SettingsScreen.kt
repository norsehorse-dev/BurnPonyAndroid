//
// SettingsScreen.kt
// Mirrors SettingsView.swift: how-it-works + honest-limits copy (verbatim
// from the iOS strings), self-hosting custom server URL field (validated,
// resettable; notes remember their server), "More pony apps" cross-promo,
// Links, version footer with the pony-family line.
//
// Phase 6: the Source code row now links to the public repository.
//

package com.burnpony.app.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnpony.app.R
import com.burnpony.app.data.api.BurnPonyApi
import com.burnpony.app.theme.BurnPonyTheme
import com.burnpony.app.ui.compose.editorColors

private const val SOURCE_REPO_URL = "https://github.com/norsehorse-dev/BurnPonyAndroid"

// Cross-promo targets (Android: link to the pony sites).
private data class PonyApp(val name: String, val subtitleRes: Int, val url: String)

private val PONY_APPS = listOf(
    PonyApp("PGPony", R.string.pony_pgpony_subtitle, "https://pgpony.app"),
    PonyApp("CarrierPony", R.string.pony_carrierpony_subtitle, "https://carrierpony.com"),
    PonyApp("AgePony", R.string.pony_agepony_subtitle, "https://agepony.com"),
    PonyApp("RelayPony", R.string.pony_relaypony_subtitle, "https://relaypony.app"),
    PonyApp("QuorumPony", R.string.pony_quorumpony_subtitle, "https://quorumpony.com"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val serverBaseUrl by viewModel.serverBaseUrl.collectAsStateWithLifecycle()
    val serverField by viewModel.serverField.collectAsStateWithLifecycle()
    val serverProblem by viewModel.serverProblem.collectAsStateWithLifecycle()

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    Scaffold(
        containerColor = BurnPonyTheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            // How BurnPony works
            SectionPanel(header = stringResource(R.string.settings_how_header)) {
                Text(
                    stringResource(R.string.settings_how_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BurnPonyTheme.ink,
                )
                Text(
                    stringResource(R.string.settings_limits_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BurnPonyTheme.dim,
                )
            }

            // Self-hosting (advanced)
            SectionPanel(header = stringResource(R.string.settings_selfhost_header)) {
                TextField(
                    value = serverField,
                    onValueChange = viewModel::setServerField,
                    placeholder = { Text(BurnPonyApi.DEFAULT_BASE_URL, color = BurnPonyTheme.dim) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { viewModel.applyServer() }),
                    modifier = Modifier.fillMaxWidth(),
                    colors = editorColors(),
                )
                if (serverProblem) {
                    Text(
                        stringResource(R.string.settings_server_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = BurnPonyTheme.danger,
                    )
                }
                if (serverBaseUrl != BurnPonyApi.DEFAULT_BASE_URL) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.settings_active_server),
                            style = MaterialTheme.typography.bodySmall,
                            color = BurnPonyTheme.dim,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            serverBaseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = BurnPonyTheme.ink,
                        )
                    }
                    TextButton(onClick = viewModel::resetServer) {
                        Text(stringResource(R.string.settings_reset_server), color = BurnPonyTheme.ember)
                    }
                }
                Text(
                    stringResource(R.string.settings_selfhost_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = BurnPonyTheme.dim,
                )
            }

            // More pony apps
            SectionPanel(header = stringResource(R.string.settings_pony_header)) {
                for (pony in PONY_APPS) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { open(pony.url) }
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(pony.name, color = BurnPonyTheme.ink)
                        Text(
                            stringResource(pony.subtitleRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = BurnPonyTheme.dim,
                        )
                    }
                }
            }

            // Links
            SectionPanel(header = stringResource(R.string.settings_links_header)) {
                Text(
                    stringResource(R.string.settings_link_website),
                    color = BurnPonyTheme.ember,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open("https://burnpony.app") }
                        .padding(vertical = 6.dp),
                )
                Text(
                    stringResource(R.string.settings_link_contact),
                    color = BurnPonyTheme.ember,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open("mailto:NorseHorse@norsehor.se") }
                        .padding(vertical = 6.dp),
                )
                // Phase 6: the open-source release is real — link the repo.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open(SOURCE_REPO_URL) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.settings_source_code), color = BurnPonyTheme.ember)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "github.com/norsehorse-dev/BurnPonyAndroid",
                        style = MaterialTheme.typography.bodySmall,
                        color = BurnPonyTheme.dim,
                    )
                }
            }

            // Version + family footer
            val version = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "1.0"
            }
            SectionPanel(header = null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_version), color = BurnPonyTheme.ink)
                    Spacer(Modifier.weight(1f))
                    Text(version, color = BurnPonyTheme.dim)
                }
            }
            Text(
                stringResource(R.string.settings_family_footer),
                style = MaterialTheme.typography.bodySmall,
                color = BurnPonyTheme.dim,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionPanel(header: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null) {
            Text(
                header.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = BurnPonyTheme.dim,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BurnPonyTheme.panel, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}
