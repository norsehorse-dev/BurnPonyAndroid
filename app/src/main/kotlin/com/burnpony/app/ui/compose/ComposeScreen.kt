//
// ComposeScreen.kt
// The New Note screen. Tester rounds folded in: prominent tappable chip row
// under the editor (the options-discoverability fix — every element opens
// the options sheet), short expiry presets listed first, device-local label
// field, Clear action with confirmation over 200 characters, keyboard
// explicitly hidden when the created-note screen presents, POST_NOTIFICATIONS
// asked at the first meaningful moment (receipt toggle, standard flavor).
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoNotTouch
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import com.burnpony.core.BurnPonyCryptoV2

// Short expiries FIRST (Tester round B1). Nine presets, shared with the
// Change Expiry dialog on the Sent list.
val EXPIRY_CHOICES = listOf(
    R.string.expiry_5_minutes to 300,
    R.string.expiry_15_minutes to 900,
    R.string.expiry_30_minutes to 1_800,
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
    val savedCopiesEnabled by viewModel.savedCopiesEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

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
    // Done, so the link cannot be lost. The keyboard is explicitly hidden
    // when it presents (Tester round B8).
    val created = state.created
    LaunchedEffect(created != null) {
        if (created != null) keyboard?.hide()
    }
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
                    // Clear (trash), disabled when empty; >200 chars confirms.
                    IconButton(
                        onClick = viewModel::requestClear,
                        enabled = !state.currentEmpty,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.compose_clear),
                            tint = if (!state.currentEmpty) BurnPonyTheme.dim else BurnPonyTheme.line,
                        )
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModePicker(state.mode, onSelect = viewModel::setMode)

            Column(modifier = Modifier.weight(1f)) {
                when (state.mode) {
                    ComposeMode.SIMPLE -> SimpleEditor(state, viewModel)
                    ComposeMode.DECOY -> DecoyEditor(state, viewModel)
                    ComposeMode.SPLIT -> SplitEditor(state, viewModel)
                }
            }

            OptionsChipRow(state, onOpenOptions = { viewModel.setShowingOptions(true) })

            CreateButton(state, onClick = viewModel::create)
        }
    }

    if (state.showingOptions) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setShowingOptions(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = BurnPonyTheme.panel,
        ) {
            OptionsSheet(state, viewModel, onReceiptToggled, savedCopiesEnabled)
        }
    }

    if (state.showingClearConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::cancelClear,
            containerColor = BurnPonyTheme.panel,
            titleContentColor = BurnPonyTheme.ink,
            textContentColor = BurnPonyTheme.dim,
            title = { Text(stringResource(R.string.clear_confirm_title)) },
            text = { Text(stringResource(R.string.clear_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmClear) {
                    Text(stringResource(R.string.compose_clear), color = BurnPonyTheme.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelClear) {
                    Text(stringResource(R.string.common_cancel), color = BurnPonyTheme.dim)
                }
            },
        )
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

//
// The options-discoverability chip row (Tester round B2): a views chip, an
// expiry chip, and small circular badges for auto-hide, passphrase,
// receipt, and instant open when enabled. Every element opens the options
// sheet.
//
@Composable
private fun OptionsChipRow(state: ComposeUiState, onOpenOptions: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OptionChip(
            icon = Icons.Filled.Visibility,
            label = stringResource(R.string.chip_views, state.effectiveViews),
            onClick = onOpenOptions,
        )
        val expiryRes = EXPIRY_CHOICES.firstOrNull { it.second == state.expirySeconds }?.first
        OptionChip(
            icon = Icons.Filled.Schedule,
            label = if (expiryRes != null) stringResource(expiryRes) else "${state.expirySeconds}s",
            onClick = onOpenOptions,
        )
        if (state.autoHideSeconds > 0) {
            OptionBadge(Icons.Filled.Timer, onOpenOptions)
        }
        if (state.mode == ComposeMode.SIMPLE && state.usePassword) {
            OptionBadge(Icons.Filled.Key, onOpenOptions)
        }
        if (state.receiptEnabled) {
            OptionBadge(Icons.Filled.Notifications, onOpenOptions)
        }
        if (state.instantOpen && !state.receiptEnabled && state.mode != ComposeMode.SPLIT) {
            OptionBadge(
                Icons.Filled.FlashOn,
                onOpenOptions,
                contentDescription = stringResource(R.string.options_instant_open),
            )
        }
        if (state.availableAtMillis != null) {
            OptionBadge(Icons.Filled.Event, onOpenOptions)
        }
        if (state.mode == ComposeMode.SIMPLE && state.noSelect) {
            OptionBadge(Icons.Filled.DoNotTouch, onOpenOptions)
        }
    }
}

@Composable
private fun OptionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(BurnPonyTheme.fieldBackground, RoundedCornerShape(999.dp))
            .border(1.dp, BurnPonyTheme.line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = BurnPonyTheme.ember, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BurnPonyTheme.ink)
    }
}

@Composable
private fun OptionBadge(icon: ImageVector, onClick: () -> Unit, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(BurnPonyTheme.fieldBackground, CircleShape)
            .border(1.dp, BurnPonyTheme.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = BurnPonyTheme.ember, modifier = Modifier.size(15.dp))
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
    savedCopiesEnabled: Boolean,
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
        ViewsStepper(views = state.viewsAllowed, onChange = viewModel::setViewsAllowed)

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

        ScheduleAvailabilitySection(state, viewModel)

        if (state.mode == ComposeMode.DECOY) {
            FooterText(stringResource(R.string.options_decoy_views_note))
        }

        // Device-local label (Tester round B4): never sent to the server.
        TextField(
            value = state.label,
            onValueChange = viewModel::setLabel,
            label = { Text(stringResource(R.string.options_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = editorColors(),
        )
        FooterText(stringResource(R.string.options_label_footer))

        // Saved copy (only when Settings has saved copies on). Device-local
        // like the label. Double bottom notes are never saved: the row stays
        // visible but disabled so the sender knows why.
        if (savedCopiesEnabled) {
            val isDecoy = state.mode == ComposeMode.DECOY
            ToggleRow(
                label = stringResource(R.string.options_save_copy),
                checked = state.saveCopy && !isDecoy,
                onCheckedChange = viewModel::setSaveCopy,
                enabled = !isDecoy,
            )
            if (isDecoy) {
                FooterText(stringResource(R.string.options_save_copy_decoy))
            }
        }

if (state.mode == ComposeMode.SIMPLE) {
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
        }

        ToggleRow(
            label = stringResource(R.string.options_read_receipt),
            checked = state.receiptEnabled,
            onCheckedChange = onReceiptToggled,
        )
        FooterText(stringResource(R.string.options_receipt_footer))

        // Instant open (per note, never a default). Skips the viewer's
        // confirmation screen, so it is unavailable with a read receipt (the
        // recipient must see the receipt disclosure first). Split links never
        // carry it, so the row is hidden there.
        if (state.mode != ComposeMode.SPLIT) {
            ToggleRow(
                label = stringResource(R.string.options_instant_open),
                checked = state.instantOpen && !state.receiptEnabled,
                onCheckedChange = viewModel::setInstantOpen,
                enabled = !state.receiptEnabled,
            )
            if (state.receiptEnabled) {
                FooterText(stringResource(R.string.options_instant_open_receipt_footer))
            } else {
                FooterText(stringResource(R.string.options_instant_open_footer))
                if ((state.mode == ComposeMode.SIMPLE && state.usePassword) || state.mode == ComposeMode.DECOY) {
                    FooterText(stringResource(R.string.options_instant_open_password_footer))
                }
            }
        }

        // Discourage copying. The footer states the limit plainly rather than
        // leaving the sender to assume this is protection: it is not, and the
        // viewer tells the recipient the same thing.
if (state.mode == ComposeMode.SIMPLE) {
        SectionHeader(stringResource(R.string.options_no_select_header))
        ToggleRow(
            label = stringResource(R.string.options_no_select),
            checked = state.noSelect,
            onCheckedChange = viewModel::setNoSelect,
        )
        FooterText(stringResource(R.string.options_no_select_footer))
        }
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

// Shared with Settings ("Defaults for new notes") so the controls and
// wording match.
@Composable
internal fun ViewsStepper(views: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.options_views_allowed, views),
            color = BurnPonyTheme.ink,
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { onChange(views - 1) },
            enabled = views > 1,
        ) {
            Icon(Icons.Filled.Remove, null, tint = if (views > 1) BurnPonyTheme.ember else BurnPonyTheme.dim)
        }
        IconButton(
            onClick = { onChange(views + 1) },
            enabled = views < 100,
        ) {
            Icon(Icons.Filled.Add, null, tint = if (views < 100) BurnPonyTheme.ember else BurnPonyTheme.dim)
        }
    }
}

@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = if (enabled) BurnPonyTheme.ink else BurnPonyTheme.dim)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = BurnPonyTheme.ember,
                checkedThumbColor = BurnPonyTheme.buttonInk,
            ),
        )
    }
}

@Composable
internal fun ChoiceRow(
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


@Composable
private fun ModePicker(mode: ComposeMode, onSelect: (ComposeMode) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeTab(stringResource(R.string.compose_mode_simple), mode == ComposeMode.SIMPLE, Modifier.weight(1f)) { onSelect(ComposeMode.SIMPLE) }
        ModeTab(stringResource(R.string.compose_mode_decoy), mode == ComposeMode.DECOY, Modifier.weight(1f)) { onSelect(ComposeMode.DECOY) }
        ModeTab(stringResource(R.string.compose_mode_split), mode == ComposeMode.SPLIT, Modifier.weight(1f)) { onSelect(ComposeMode.SPLIT) }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        color = if (selected) BurnPonyTheme.buttonInk else BurnPonyTheme.dim,
        modifier = modifier
            .background(if (selected) BurnPonyTheme.ember else BurnPonyTheme.fieldBackground, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

@Composable
private fun ColumnScope.SimpleEditor(state: ComposeUiState, viewModel: ComposeViewModel) {
    TextField(
        value = state.text,
        onValueChange = viewModel::setText,
        modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, BurnPonyTheme.line, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = editorColors(),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.compose_counter, state.text.length, BurnPonyLimits.MAX_NOTE_CHARACTERS),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.overLimit) BurnPonyTheme.danger else BurnPonyTheme.dim,
    )
}

@Composable
private fun compartmentTag(index: Int): String = stringResource(
    when (index) {
        0 -> R.string.compose_decoy_tag_decoy
        1 -> R.string.compose_decoy_tag_real
        else -> R.string.compose_decoy_tag_deeper
    }
)

@Composable
private fun decoyProblemText(p: DecoyProblem): String = stringResource(
    when (p) {
        DecoyProblem.NEED_TWO -> R.string.compose_decoy_need_two
        DecoyProblem.NEED_PASS -> R.string.compose_decoy_need_pass
        DecoyProblem.DISTINCT -> R.string.compose_decoy_distinct
    }
)

@Composable
private fun ColumnScope.DecoyEditor(state: ComposeUiState, viewModel: ComposeViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FooterText(stringResource(R.string.compose_decoy_intro))
        state.compartments.forEachIndexed { index, comp ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(BurnPonyTheme.panel, RoundedCornerShape(12.dp))
                    .border(1.dp, BurnPonyTheme.line, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(compartmentTag(index), style = MaterialTheme.typography.labelSmall, color = BurnPonyTheme.ember)
                TextField(
                    value = comp.text,
                    onValueChange = { viewModel.setCompartmentText(index, it) },
                    label = { Text(stringResource(R.string.compose_decoy_message)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = editorColors(),
                )
                TextField(
                    value = comp.password,
                    onValueChange = { viewModel.setCompartmentPassword(index, it) },
                    label = { Text(stringResource(R.string.compose_decoy_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = editorColors(),
                )
            }
        }
        if (state.compartments.size < BurnPonyCryptoV2.DECOY_SLOTS) {
            TextButton(onClick = viewModel::addCompartment) {
                Icon(Icons.Filled.Add, null, tint = BurnPonyTheme.ember, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.compose_decoy_add), color = BurnPonyTheme.ember)
            }
        }
        val problem = state.decoyProblem
        if (problem != null && !state.currentEmpty) {
            Text(decoyProblemText(problem), style = MaterialTheme.typography.bodySmall, color = BurnPonyTheme.danger)
        }
    }
}

@Composable
private fun StepperRow(label: String, canMinus: Boolean, canPlus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = BurnPonyTheme.ink)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onMinus, enabled = canMinus) {
            Icon(Icons.Filled.Remove, null, tint = if (canMinus) BurnPonyTheme.ember else BurnPonyTheme.dim)
        }
        IconButton(onClick = onPlus, enabled = canPlus) {
            Icon(Icons.Filled.Add, null, tint = if (canPlus) BurnPonyTheme.ember else BurnPonyTheme.dim)
        }
    }
}

@Composable
private fun ColumnScope.SplitEditor(state: ComposeUiState, viewModel: ComposeViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextField(
            value = state.splitText,
            onValueChange = viewModel::setSplitText,
            label = { Text(stringResource(R.string.compose_split_note)) },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            colors = editorColors(),
        )
        Text(
            text = stringResource(R.string.compose_counter, state.splitText.length, BurnPonyLimits.MAX_NOTE_CHARACTERS),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.overLimit) BurnPonyTheme.danger else BurnPonyTheme.dim,
        )
        StepperRow(stringResource(R.string.compose_split_total, state.splitN), state.splitN > 2, state.splitN < 8,
            onMinus = { viewModel.setSplitN(state.splitN - 1) }, onPlus = { viewModel.setSplitN(state.splitN + 1) })
        StepperRow(stringResource(R.string.compose_split_required, state.splitT), state.splitT > 2, state.splitT < state.splitN,
            onMinus = { viewModel.setSplitT(state.splitT - 1) }, onPlus = { viewModel.setSplitT(state.splitT + 1) })
        ToggleRow(stringResource(R.string.compose_split_per_key), state.splitPerKeyPassphrase, viewModel::setSplitPerKeyPassphrase)
        if (state.splitPerKeyPassphrase) {
            for (i in 0 until state.splitN) {
                TextField(
                    value = state.splitKeyPasswords[i],
                    onValueChange = { viewModel.setSplitKeyPassword(i, it) },
                    label = { Text(stringResource(R.string.compose_split_key_passphrase, i + 1)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = editorColors(),
                )
            }
        }
        FooterText(stringResource(R.string.compose_split_summary, state.splitT, state.splitN))
    }
}

//
// Scheduled availability (shared option, all modes). A toggle reveals a date
// row and a time row; each opens a Material3 picker. The chosen wall-clock
// time is stored as absolute epoch millis in the ViewModel and converted to
// seconds-from-now at create() time. Must be before expiry; that conflict is
// validated in the ViewModel and surfaced as the standard error alert.
//
private const val SCHEDULE_DEFAULT_OFFSET_MS = 3_600_000L // +1 hour

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleAvailabilitySection(state: ComposeUiState, viewModel: ComposeViewModel) {
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val scheduledMillis = state.availableAtMillis

    ToggleRow(
        label = stringResource(R.string.options_schedule_availability),
        checked = scheduledMillis != null,
        onCheckedChange = { on ->
            viewModel.setAvailableAtMillis(
                if (on) System.currentTimeMillis() + SCHEDULE_DEFAULT_OFFSET_MS else null
            )
        },
    )

    if (scheduledMillis != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.options_available_date), color = BurnPonyTheme.ink)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showDatePicker = true }) {
                Text(formatScheduleDate(scheduledMillis), color = BurnPonyTheme.ember)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.options_available_time), color = BurnPonyTheme.ink)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showTimePicker = true }) {
                Text(formatScheduleTime(scheduledMillis), color = BurnPonyTheme.ember)
            }
        }
    }
    FooterText(stringResource(R.string.options_available_footer))

    if (showDatePicker && scheduledMillis != null) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = localDayToUtcMillis(scheduledMillis)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { picked ->
                        viewModel.setAvailableAtMillis(combineScheduleDate(picked, scheduledMillis))
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.common_done), color = BurnPonyTheme.ember) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.common_cancel), color = BurnPonyTheme.dim)
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTimePicker && scheduledMillis != null) {
        val base = java.util.Calendar.getInstance().apply { timeInMillis = scheduledMillis }
        val timeState = rememberTimePickerState(
            initialHour = base.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = base.get(java.util.Calendar.MINUTE),
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = BurnPonyTheme.panel,
            titleContentColor = BurnPonyTheme.ink,
            title = { Text(stringResource(R.string.options_available_time)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setAvailableAtMillis(
                        withScheduleTime(scheduledMillis, timeState.hour, timeState.minute)
                    )
                    showTimePicker = false
                }) { Text(stringResource(R.string.common_done), color = BurnPonyTheme.ember) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.common_cancel), color = BurnPonyTheme.dim)
                }
            },
        )
    }
}

private fun formatScheduleDate(millis: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))

private fun formatScheduleTime(millis: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(millis))

/** UTC-midnight of the local calendar day, to seed the (UTC-based) DatePicker. */
private fun localDayToUtcMillis(localMillis: Long): Long {
    val local = java.util.Calendar.getInstance().apply { timeInMillis = localMillis }
    return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            local.get(java.util.Calendar.YEAR),
            local.get(java.util.Calendar.MONTH),
            local.get(java.util.Calendar.DAY_OF_MONTH),
            0, 0, 0,
        )
    }.timeInMillis
}

/** Combine a UTC-midnight date (from DatePicker) with the wall-clock time of [timeSourceMillis]. */
private fun combineScheduleDate(pickedUtcMidnight: Long, timeSourceMillis: Long): Long {
    val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        .apply { timeInMillis = pickedUtcMidnight }
    val timeSrc = java.util.Calendar.getInstance().apply { timeInMillis = timeSourceMillis }
    return java.util.Calendar.getInstance().apply {
        clear()
        set(
            utc.get(java.util.Calendar.YEAR),
            utc.get(java.util.Calendar.MONTH),
            utc.get(java.util.Calendar.DAY_OF_MONTH),
            timeSrc.get(java.util.Calendar.HOUR_OF_DAY),
            timeSrc.get(java.util.Calendar.MINUTE),
            0,
        )
    }.timeInMillis
}

/** Keep the date of [dateSourceMillis], set the wall-clock hour/minute. */
private fun withScheduleTime(dateSourceMillis: Long, hour: Int, minute: Int): Long {
    val src = java.util.Calendar.getInstance().apply { timeInMillis = dateSourceMillis }
    return java.util.Calendar.getInstance().apply {
        clear()
        set(
            src.get(java.util.Calendar.YEAR),
            src.get(java.util.Calendar.MONTH),
            src.get(java.util.Calendar.DAY_OF_MONTH),
            hour, minute, 0,
        )
    }.timeInMillis
}
