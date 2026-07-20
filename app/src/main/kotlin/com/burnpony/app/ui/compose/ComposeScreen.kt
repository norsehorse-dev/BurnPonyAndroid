//
// ComposeScreen.kt
// The New Note screen, mirroring ComposeView.swift: editor with live counter
// against 50,000, options sheet (views stepper 1-100, expiry picker, auto-hide
// picker, passphrase toggle with confirm, read receipt toggle with the
// anti-silent-tracking footer), Create button with progress state.
//
// Phase 4: enabling the read-receipt toggle is the first meaningful moment
// for POST_NOTIFICATIONS (standard flavor, API 33+); it is never requested
// at launch, and the foss flavor never requests it at all.
//

package com.burnpony.app.ui.compose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnpony.app.R
import com.burnpony.app.push.PushSupport
import com.burnpony.app.theme.BurnPonyTheme
import com.burnpony.app.ui.result.ResultScreen
import com.burnpony.core.BurnPonyLimits

val EXPIRY_CHOICES = listOf(
    R.string.expiry_1_hour to 3_600,
    R.string.expiry_8_hours to 28_800,
    R.string.expiry_1_day to 86_400,
    R.string.expiry_3_days to 259_200,
    R.string.expiry_7_days to 604_800,
    R.string.expiry_30_days to 2_592_000,
)

val AUTOHIDE_CHOICES = listOf(
    R.string.autohide_off to 0,
    R.string.autohide_10_seconds to 10,
    R.string.autohide_30_seconds to 30,
    R.string.autohide_1_minute to 60,
    R.string.autohide_2_minutes to 120,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(viewModel: ComposeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // POST_NOTIFICATIONS at the first meaningful moment: turning receipts on.
    // Denial is fine — receipts still arrive via polling in Sent Notes.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val onReceiptToggled: (Boolean) -> Unit = { enabled ->
        if (enabled &&
            PushSupport.wantsNotificationPermission &&
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.setReceiptEnabled(enabled)
    }

    // Result is a full-screen takeover dismissable only via its explicit
    // Done, so the link cannot be lost (iOS: interactiveDismissDisabled).
    val created = state.created
    if (created != null) {
        ResultScreen(note = created, onDone = { viewModel.resetForm() })
        return
    }

    Scaffold(
        containerColor = BurnPonyTheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BurnPonyTheme.background,
                    titleContentColor = BurnPonyTheme.ink,
                ),
                actions = {
                    TextButton(onClick = { viewModel.setShowingOptions(true) }) {
                        Text(stringResource(R.string.compose_options), color = BurnPonyTheme.ember)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextField(
                    value = state.text,
                    onValueChange = viewModel::setText,
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, BurnPonyTheme.line, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    colors = editorColors(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.compose_counter,
                        state.text.length,
                        BurnPonyLimits.MAX_NOTE_CHARACTERS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.overLimit) BurnPonyTheme.danger else BurnPonyTheme.dim,
                )
            }

            OptionsSummary(state)

            CreateButton(state, onClick = viewModel::create)
        }
    }

    if (state.showingOptions) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setShowingOptions(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = BurnPonyTheme.panel,
        ) {
            OptionsSheet(state, viewModel, onReceiptToggled)
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            containerColor = BurnPonyTheme.panel,
            titleContentColor = BurnPonyTheme.ink,
            textContentColor = BurnPonyTheme.dim,
            title = { Text(stringResource(R.string.compose_error_title)) },
            text = { Text(stringResource(error.resId, *error.args.toTypedArray())) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) {
                    Text(stringResource(R.string.common_ok), color = BurnPonyTheme.ember)
                }
            },
        )
    }
}

@Composable
private fun OptionsSummary(state: ComposeUiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SummaryItem(Icons.Filled.Visibility, "${state.viewsAllowed}")
        val expiryRes = EXPIRY_CHOICES.firstOrNull { it.second == state.expirySeconds }?.first
        if (expiryRes != null) {
            SummaryItem(Icons.Filled.Schedule, stringResource(expiryRes))
        }
        if (state.usePassword) {
            Icon(Icons.Filled.Key, null, tint = BurnPonyTheme.dim, modifier = Modifier.size(14.dp))
        }
        if (state.receiptEnabled) {
            Icon(Icons.Filled.Notifications, null, tint = BurnPonyTheme.dim, modifier = Modifier.size(14.dp))
        }
        if (state.autoHideSeconds > 0) {
            Icon(Icons.Filled.Timer, null, tint = BurnPonyTheme.dim, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SummaryItem(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = BurnPonyTheme.dim, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = BurnPonyTheme.dim)
    }
}

@Composable
private fun CreateButton(state: ComposeUiState, onClick: () -> Unit) {
    val alpha = if (state.canCreate) 1f else 0.45f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BurnPonyTheme.emberGradient, RoundedCornerShape(12.dp))
            .clickable(enabled = state.canCreate, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.creating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = BurnPonyTheme.buttonInk,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = stringResource(
                    if (state.creating) R.string.compose_creating else R.string.compose_create
                ),
                color = BurnPonyTheme.buttonInk.copy(alpha = alpha),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun OptionsSheet(
    state: ComposeUiState,
    viewModel: ComposeViewModel,
    onReceiptToggled: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.options_title),
                style = MaterialTheme.typography.titleMedium,
                color = BurnPonyTheme.ink,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.setShowingOptions(false) }) {
                Text(stringResource(R.string.common_done), color = BurnPonyTheme.ember)
            }
        }

        SectionHeader(stringResource(R.string.options_self_destruction))

        // Views stepper 1-100, default 1
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.options_views_allowed, state.viewsAllowed),
                color = BurnPonyTheme.ink,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { viewModel.setViewsAllowed(state.viewsAllowed - 1) },
                enabled = state.viewsAllowed > 1,
            ) {
                Icon(Icons.Filled.Remove, null, tint = if (state.viewsAllowed > 1) BurnPonyTheme.ember else BurnPonyTheme.dim)
            }
            IconButton(
                onClick = { viewModel.setViewsAllowed(state.viewsAllowed + 1) },
                enabled = state.viewsAllowed < 100,
            ) {
                Icon(Icons.Filled.Add, null, tint = if (state.viewsAllowed < 100) BurnPonyTheme.ember else BurnPonyTheme.dim)
            }
        }

        Text(
            stringResource(R.string.options_expires_after),
            color = BurnPonyTheme.ink,
        )
        ChoiceRow(
            choices = EXPIRY_CHOICES,
            selected = state.expirySeconds,
            onSelect = viewModel::setExpirySeconds,
        )

        Text(
            stringResource(R.string.options_auto_hide),
            color = BurnPonyTheme.ink,
        )
        ChoiceRow(
            choices = AUTOHIDE_CHOICES,
            selected = state.autoHideSeconds,
            onSelect = viewModel::setAutoHideSeconds,
        )

        SectionHeader(stringResource(R.string.options_second_factor))

        ToggleRow(
            label = stringResource(R.string.options_require_passphrase),
            checked = state.usePassword,
            onCheckedChange = viewModel::setUsePassword,
        )
        if (state.usePassword) {
            TextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text(stringResource(R.string.options_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = editorColors(),
            )
            TextField(
                value = state.passwordConfirm,
                onValueChange = viewModel::setPasswordConfirm,
                label = { Text(stringResource(R.string.options_confirm_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = editorColors(),
            )
            state.passwordProblem?.let { problem ->
                Text(
                    stringResource(problem),
                    style = MaterialTheme.typography.bodySmall,
                    color = BurnPonyTheme.danger,
                )
            }
        }
        FooterText(stringResource(R.string.options_passphrase_footer))

        ToggleRow(
            label = stringResource(R.string.options_read_receipt),
            checked = state.receiptEnabled,
            onCheckedChange = onReceiptToggled,
        )
        FooterText(stringResource(R.string.options_receipt_footer))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = BurnPonyTheme.dim,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun FooterText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = BurnPonyTheme.dim,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = BurnPonyTheme.ink)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = BurnPonyTheme.ember,
                checkedThumbColor = BurnPonyTheme.buttonInk,
            ),
        )
    }
}

@Composable
private fun ChoiceRow(
    choices: List<Pair<Int, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((labelRes, value) in choices) {
            val isSelected = value == selected
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) BurnPonyTheme.buttonInk else BurnPonyTheme.ink,
                modifier = Modifier
                    .background(
                        if (isSelected) BurnPonyTheme.ember else BurnPonyTheme.fieldBackground,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun editorColors() = TextFieldDefaults.colors(
    focusedContainerColor = BurnPonyTheme.fieldBackground,
    unfocusedContainerColor = BurnPonyTheme.fieldBackground,
    focusedTextColor = BurnPonyTheme.ink,
    unfocusedTextColor = BurnPonyTheme.ink,
    cursorColor = BurnPonyTheme.ember,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    focusedLabelColor = BurnPonyTheme.dim,
    unfocusedLabelColor = BurnPonyTheme.dim,
)
