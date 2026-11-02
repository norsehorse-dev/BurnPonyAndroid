//
// SentNotesScreen.kt
// Tester rounds folded in: device-local labels as the primary line (date
// secondary; unlabeled notes show the date as before), Active in GREEN,
// Clear Inactive toolbar action (visible only when an inactive note exists),
// leading swipe = Change Expiry on active notes with the nine-preset
// "New expiration" dialog ("Counted from now."), trailing swipe = burn with
// confirmation or remove when burned/gone. Offline keeps last known status.
//
// Saved copies: rows holding one show a small document icon and a visible
// "Show saved copy" action. Opening it runs BiometricPrompt against the
// Keystore key (CryptoObject on API 30+, timed unlock on API 24-29), then
// shows the text full screen with FLAG_SECURE on both the activity window
// and the dialog window. Leaving the app closes the copy.
//

package com.burnpony.app.ui.sent

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnpony.app.R
import com.burnpony.app.data.db.SentNoteEntity
import com.burnpony.app.theme.BurnPonyTheme
import com.burnpony.app.ui.compose.EXPIRY_CHOICES
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentNotesScreen(viewModel: SentNotesViewModel) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val burnTarget by viewModel.burnTarget.collectAsStateWithLifecycle()
    val expiryTarget by viewModel.expiryTarget.collectAsStateWithLifecycle()
    val showClearInactive by viewModel.showClearInactive.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val openedCopy by viewModel.openedCopy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val unlockTitle = stringResource(R.string.saved_copy_auth_title)

    // Refresh on entry, like .task on iOS.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Clear Inactive skips records holding a saved copy, so it only shows
    // when something would actually be removed.
    val hasInactive = notes.any { (it.burned || it.goneFromServer) && it.savedCopy == null }
    val inactiveKeptForCopies = notes.any { (it.burned || it.goneFromServer) && it.savedCopy != null }

    Scaffold(
        containerColor = BurnPonyTheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sent_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BurnPonyTheme.background,
                    titleContentColor = BurnPonyTheme.ink,
                ),
                actions = {
                    if (hasInactive) {
                        TextButton(onClick = viewModel::requestClearInactive) {
                            Text(
                                stringResource(R.string.sent_clear_inactive),
                                color = BurnPonyTheme.dim,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (notes.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(notes, key = { it.id }) { note ->
                        key(note.id) {
                            SwipeableNoteRow(
                                note = note,
                                receiptTimes = viewModel.receiptTimes(note),
                                onBurn = { viewModel.requestBurn(note) },
                                onRemove = { viewModel.remove(note) },
                                onChangeExpiry = { viewModel.requestExpiryChange(note) },
                                onShowSavedCopy = {
                                    val activity = context.findFragmentActivity()
                                    if (activity == null) {
                                        viewModel.reportSavedCopyUnavailable()
                                    } else {
                                        unlockSavedCopy(activity, note.id, unlockTitle, viewModel)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    burnTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelBurn,
            containerColor = BurnPonyTheme.panel,
            titleContentColor = BurnPonyTheme.ink,
            textContentColor = BurnPonyTheme.dim,
            title = { Text(stringResource(R.string.sent_burn_confirm_title)) },
            text = { Text(stringResource(R.string.sent_burn_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmBurn) {
                    Text(stringResource(R.string.sent_burn_confirm_action), color = BurnPonyTheme.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelBurn) {
                    Text(stringResource(R.string.common_cancel), color = BurnPonyTheme.dim)
                }
            },
        )
    }

    // Change Expiry dialog (B11): nine presets, counted from now.
    expiryTarget?.let {
        AlertDialog(
            onDismissRequest = viewModel::cancelExpiryChange,
            containerColor = BurnPonyTheme.panel,
            titleContentColor = BurnPonyTheme.ink,
            textContentColor = BurnPonyTheme.dim,
            title = { Text(stringResource(R.string.expiry_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.expiry_dialog_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = BurnPonyTheme.dim,
                    )
                    for ((labelRes, seconds) in EXPIRY_CHOICES) {
                        TextButton(
                            onClick = { viewModel.changeExpiry(seconds) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(labelRes),
                                color = BurnPonyTheme.ink,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = { },
            dismissButton = {
                TextButton(onClick = viewModel::cancelExpiryChange) {
                    Text(stringResource(R.string.common_cancel), color = BurnPonyTheme.dim)
                }
            },
        )
    }

    if (showClearInactive) {
        AlertDialog(
            onDismissRequest = viewModel::cancelClearInactive,
            containerColor = BurnPonyTheme.panel,
            titleContentColor = BurnPonyTheme.ink,
            textContentColor = BurnPonyTheme.dim,
            title = { Text(stringResource(R.string.sent_clear_inactive_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sent_clear_inactive_message))
                    if (inactiveKeptForCopies) {
                        Text(stringResource(R.string.sent_clear_keeps_copies))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmClearInactive) {
                    Text(stringResource(R.string.sent_remove), color = BurnPonyTheme.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelClearInactive) {
                    Text(stringResource(R.string.common_cancel), color = BurnPonyTheme.dim)
                }
            },
        )
    }

    openedCopy?.let { copy ->
        // Backgrounding the app closes the copy; reopening asks again.
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.closeSavedCopy() }
        SavedCopyDialog(
            text = copy.text,
            onClose = viewModel::closeSavedCopy,
            onDelete = viewModel::deleteOpenedSavedCopy,
        )
    }

    error?.let { uiError ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            containerColor = BurnPonyTheme.panel,
            titleContentColor = BurnPonyTheme.ink,
            textContentColor = BurnPonyTheme.dim,
            title = { Text(stringResource(R.string.sent_problem_title)) },
            text = { Text(stringResource(uiError.resId, *uiError.args.toTypedArray())) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) {
                    Text(stringResource(R.string.common_ok), color = BurnPonyTheme.ember)
                }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.LocalFireDepartment,
            null,
            tint = BurnPonyTheme.dim,
            modifier = Modifier.size(44.dp),
        )
        Text(
            stringResource(R.string.sent_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = BurnPonyTheme.ink,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            stringResource(R.string.sent_empty_description),
            style = MaterialTheme.typography.bodySmall,
            color = BurnPonyTheme.dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNoteRow(
    note: SentNoteEntity,
    receiptTimes: List<Long>,
    onBurn: () -> Unit,
    onRemove: () -> Unit,
    onChangeExpiry: () -> Unit,
    onShowSavedCopy: () -> Unit,
) {
    val removable = note.burned || note.goneFromServer
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart ->
                    if (removable) onRemove() else onBurn()
                SwipeToDismissBoxValue.StartToEnd ->
                    if (!removable) onChangeExpiry()
                else -> {}
            }
            // Never auto-dismiss: removal happens through the store; burn and
            // expiry-change need their dialogs first.
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !removable,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val leading = direction == SwipeToDismissBoxValue.StartToEnd
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        when {
                            leading -> BurnPonyTheme.line
                            removable -> BurnPonyTheme.line
                            else -> BurnPonyTheme.danger
                        },
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = if (leading) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (leading) {
                        Icon(Icons.Filled.Schedule, null, tint = BurnPonyTheme.ink)
                        Text(
                            stringResource(R.string.sent_change_expiry),
                            color = BurnPonyTheme.ink,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Icon(
                            if (removable) Icons.Filled.Delete else Icons.Filled.LocalFireDepartment,
                            null,
                            tint = BurnPonyTheme.ink,
                        )
                        Text(
                            stringResource(if (removable) R.string.sent_remove else R.string.sent_burn),
                            color = BurnPonyTheme.ink,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
    ) {
        SentNoteRow(note, receiptTimes, onShowSavedCopy)
    }
}

@Composable
private fun SentNoteRow(note: SentNoteEntity, receiptTimes: List<Long>, onShowSavedCopy: () -> Unit) {
    val context = LocalContext.current
    val stateLabel: Pair<Int, Color> = when {
        note.goneFromServer -> R.string.sent_state_gone to BurnPonyTheme.dim
        note.burned -> R.string.sent_state_burned to BurnPonyTheme.danger
        else -> R.string.sent_state_active to BurnPonyTheme.activeGreen
    }
    val created = remember(note.createdAtEpochMs) {
        DateFormat.getMediumDateFormat(context).format(Date(note.createdAtEpochMs)) +
            " " + DateFormat.getTimeFormat(context).format(Date(note.createdAtEpochMs))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BurnPonyTheme.panel, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Label primary, date secondary; unlabeled shows the date as before.
            Column(modifier = Modifier.weight(1f)) {
                if (note.label != null) {
                    Text(
                        note.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BurnPonyTheme.ink,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        created,
                        style = MaterialTheme.typography.bodySmall,
                        color = BurnPonyTheme.dim,
                    )
                } else {
                    Text(
                        created,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BurnPonyTheme.ink,
                    )
                }
            }
            Text(
                stringResource(stateLabel.first),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = stateLabel.second,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgeItem(
                Icons.Filled.Visibility,
                stringResource(R.string.sent_views, note.viewsUsed, note.viewsAllowed),
            )
            if (!note.burned && !note.goneFromServer) {
                BadgeItem(
                    Icons.Filled.Schedule,
                    DateUtils.getRelativeTimeSpanString(
                        note.expiresAtEpochMs,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                )
            }
            if (note.hasPassword) {
                Icon(Icons.Filled.Key, null, tint = BurnPonyTheme.dim, modifier = Modifier.size(14.dp))
            }
            if (note.autoHideSeconds > 0) {
                Icon(Icons.Filled.Timer, null, tint = BurnPonyTheme.dim, modifier = Modifier.size(14.dp))
            }
            if (note.savedCopy != null) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = stringResource(R.string.saved_copy_badge),
                    tint = BurnPonyTheme.dim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (note.receiptEnabled) {
            if (receiptTimes.isEmpty()) {
                BadgeItem(Icons.Filled.Notifications, stringResource(R.string.sent_no_opens))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (time in receiptTimes) {
                        val opened = DateFormat.getMediumDateFormat(context).format(Date(time)) +
                            " " + DateFormat.getTimeFormat(context).format(Date(time))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.NotificationsActive,
                                null,
                                tint = BurnPonyTheme.ember,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                stringResource(R.string.sent_opened_at, opened),
                                style = MaterialTheme.typography.bodySmall,
                                color = BurnPonyTheme.ember,
                            )
                        }
                    }
                }
            }
        }
        if (note.savedCopy != null) {
            TextButton(onClick = onShowSavedCopy) {
                Text(stringResource(R.string.saved_copy_show), color = BurnPonyTheme.ember)
            }
        }
    }
}

@Composable
private fun BadgeItem(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = BurnPonyTheme.dim, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = BurnPonyTheme.dim)
    }
}

// ── Saved copies ─────────────────────────────────────────────────────────

/** AppCompat may wrap the Activity context (per-app language), so unwrap. */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Unlocks the Keystore key and opens the copy for [noteId].
 *
 * API 30+: the key needs a fresh auth per use, so the decrypt cipher goes
 * into the prompt as a CryptoObject (BIOMETRIC_STRONG or DEVICE_CREDENTIAL,
 * valid with a CryptoObject from API 30 in androidx.biometric 1.1.0).
 *
 * API 24-29: a CryptoObject cannot be combined with the screen lock there,
 * so the key is time-bound instead. The prompt runs without a CryptoObject
 * (BIOMETRIC_WEAK or DEVICE_CREDENTIAL, the combination 1.1.0 supports on
 * every API level) and the copy is opened inside the unlock window.
 */
private fun unlockSavedCopy(
    activity: FragmentActivity,
    noteId: String,
    title: String,
    viewModel: SentNotesViewModel,
) {
    val keyguard = activity.getSystemService(KeyguardManager::class.java)
    if (keyguard == null || !keyguard.isDeviceSecure) {
        // Without a screen lock the key no longer exists.
        viewModel.reportSavedCopyUnavailable()
        return
    }
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (Build.VERSION.SDK_INT >= 30) {
                val cipher = result.cryptoObject?.cipher
                if (cipher == null) {
                    viewModel.reportSavedCopyUnavailable()
                } else {
                    viewModel.openSavedCopy(noteId, cipher)
                }
            } else {
                viewModel.openSavedCopy(noteId, null)
            }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // Cancel, back and lockout are the prompt's own business; only a
            // missing screen lock means the copy cannot be opened.
            if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                viewModel.reportSavedCopyUnavailable()
            }
        }
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
    if (Build.VERSION.SDK_INT >= 30) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        viewModel.prepareSavedCopyUnlock { cipher ->
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
    } else {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}

/**
 * Full-screen view of an opened copy. FLAG_SECURE is set on the dialog's own
 * window (securePolicy) and on the activity window while it is shown, so
 * neither screenshots nor the recents thumbnail capture the text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedCopyDialog(
    text: String,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    val activity = LocalContext.current.findFragmentActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = BurnPonyTheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.saved_copy_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BurnPonyTheme.background,
                        titleContentColor = BurnPonyTheme.ink,
                    ),
                    actions = {
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.common_done), color = BurnPonyTheme.ember)
                        }
                    },
                )
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = BurnPonyTheme.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.saved_copy_delete), color = BurnPonyTheme.danger)
                }
            }
        }
    }
}
