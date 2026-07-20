//
// SentNotesScreen.kt
// Diego rounds folded in: device-local labels as the primary line (date
// secondary; unlabeled notes show the date as before), Active in GREEN,
// Clear Inactive toolbar action (visible only when an inactive note exists),
// leading swipe = Change Expiry on active notes with the nine-preset
// "New expiration" dialog ("Counted from now."), trailing swipe = burn with
// confirmation or remove when burned/gone. Offline keeps last known status.
//

package com.burnpony.app.ui.sent

import android.text.format.DateFormat
import android.text.format.DateUtils
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

    // Refresh on entry, like .task on iOS.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val hasInactive = notes.any { it.burned || it.goneFromServer }

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
            text = { Text(stringResource(R.string.sent_clear_inactive_message)) },
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
        SentNoteRow(note, receiptTimes)
    }
}

@Composable
private fun SentNoteRow(note: SentNoteEntity, receiptTimes: List<Long>) {
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
