//
// OnboardingSendSheet.kt
// The live step of the first-run walkthrough. This is a real send: the same
// BurnPonyCrypto path ComposeScreen uses, the same relay, and a real row in
// Sent Notes with a working burn button.
//
// The tour fixes the note options rather than exposing them:
//   * one view        - the burn is immediate and observable
//   * five minutes    - a forgotten tour note cannot linger
//   * no passphrase   - a second factor chosen before the concept has been
//                       explained is a passphrase nobody remembers
//   * no read receipt - receipts trigger the POST_NOTIFICATIONS prompt, and
//                       asking for notification permission inside a tutorial
//                       is exactly the pattern that trains people to deny it
//
// Those choices are stated in the sheet, not hidden. Slide 2 already told
// the user the options exist; this step is about proving the pipeline.
//

package com.burnpony.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnpony.app.R
import com.burnpony.app.theme.BurnPonyTheme
import com.burnpony.app.ui.compose.editorColors
import com.burnpony.core.BurnPonyLimits
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSendSheet(
    viewModel: OnboardingViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Reject the DISMISS GESTURE rather than the dismiss CALLBACK while a
    // send is in flight. Material3 animates the sheet to Hidden before it
    // calls onDismissRequest, so swallowing the callback would leave a
    // settled-hidden sheet that nothing can bring back - and with OkHttp's
    // 10s connect / 20s read timeouts that window is wide on a bad network.
    // Hoisted and keyed on the view model: rememberModalBottomSheetState uses
    // confirmValueChange as a rememberSaveable KEY, so a lambda that changed
    // identity per recomposition would rebuild the SheetState (resetting it to
    // Hidden) on every keystroke.
    val confirmSheetChange = remember(viewModel) {
        { _: SheetValue -> !viewModel.state.value.creating }
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = confirmSheetChange,
    )
    val scope = rememberCoroutineScope()

    fun dismissAnimated() {
        // invokeOnCompletion fires on cancellation too, so confirm the sheet
        // actually finished hiding before reporting a dismissal.
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }
    val tourLabel = stringResource(R.string.onboarding_send_label)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BurnPonyTheme.panel,
        contentColor = BurnPonyTheme.ink,
    ) {
        Column(
            // imePadding BEFORE verticalScroll: applied after, the keyboard
            // inset becomes scrolling content instead of shrinking the
            // viewport, and the create button ends up under the keyboard with
            // no way to scroll to it. The sheet's own contentWindowInsets
            // already handles the navigation bar, so no padding for that here.
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = BurnPonyTheme.ink,
            )
            Text(
                stringResource(R.string.onboarding_sheet_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = BurnPonyTheme.dim,
            )

            TextField(
                value = state.text,
                onValueChange = viewModel::setText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp),
                placeholder = {
                    Text(
                        stringResource(R.string.onboarding_sheet_placeholder),
                        color = BurnPonyTheme.dim,
                    )
                },
                colors = editorColors(),
            )
            Text(
                stringResource(
                    R.string.compose_counter,
                    state.text.length,
                    BurnPonyLimits.MAX_NOTE_CHARACTERS,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.overLimit) BurnPonyTheme.danger else BurnPonyTheme.dim,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TourChip(Icons.Filled.Visibility, stringResource(R.string.onboarding_chip_one_view))
                TourChip(Icons.Filled.Schedule, stringResource(R.string.onboarding_chip_five_minutes))
            }
            Text(
                stringResource(R.string.onboarding_sheet_fixed_options),
                style = MaterialTheme.typography.bodySmall,
                color = BurnPonyTheme.dim,
            )

            // Same gradient-Box pattern as ComposeScreen.CreateButton, so the
            // tour's create button reads as the one the user will press for
            // every note afterwards.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (state.canCreate) 1f else 0.45f)
                    .background(BurnPonyTheme.emberGradient, RoundedCornerShape(12.dp))
                    .clickable(enabled = state.canCreate) { viewModel.create(tourLabel) }
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
                        stringResource(
                            if (state.creating) R.string.compose_creating else R.string.compose_create
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BurnPonyTheme.buttonInk,
                    )
                }
            }

            TextButton(
                onClick = { dismissAnimated() },
                enabled = !state.creating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_sheet_later), color = BurnPonyTheme.dim)
            }
        }
    }

    val error = state.error
    if (error != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(stringResource(R.string.compose_error_title)) },
            text = { Text(stringResource(error.resId, *error.args.toTypedArray())) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) {
                    Text(stringResource(R.string.common_ok), color = BurnPonyTheme.ember)
                }
            },
            containerColor = BurnPonyTheme.panel,
            titleContentColor = BurnPonyTheme.ink,
            textContentColor = BurnPonyTheme.dim,
        )
    }
}

@Composable
private fun TourChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(BurnPonyTheme.fieldBackground)
            .border(1.dp, BurnPonyTheme.ember.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Icon(icon, contentDescription = null, tint = BurnPonyTheme.ember, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = BurnPonyTheme.ink,
        )
    }
}
