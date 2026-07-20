//
// ResultScreen.kt
// Mirrors ResultView.swift: dismissal ONLY via the explicit Done so the link
// cannot be lost (back press is swallowed). Link middle-truncated in
// monospace, system share sheet, copy with confirmation, QR on a cream tile
// for in-person handoff, send-the-passphrase-separately reminder when set,
// expiry line.
//

package com.burnpony.app.ui.result

import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.burnpony.app.R
import com.burnpony.app.data.CreatedNote
import com.burnpony.app.theme.BurnPonyTheme
import kotlinx.coroutines.delay
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(note: CreatedNote, onDone: () -> Unit) {
    // Swallow back presses: Done is the only way out, so the link is never
    // lost to an accidental gesture.
    BackHandler(enabled = true) { }

    Scaffold(
        containerColor = BurnPonyTheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.result_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BurnPonyTheme.background,
                    titleContentColor = BurnPonyTheme.ink,
                ),
                actions = {
                    TextButton(onClick = onDone) {
                        Text(stringResource(R.string.common_done), color = BurnPonyTheme.ember)
                    }
                },
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
            if (note.hasPassword) {
                PassphraseReminder()
            }
            LinkCard(note)
            QrCard(note)
            val context = LocalContext.current
            val expiresText = remember(note.expiresAtEpochMs) {
                DateFormat.getMediumDateFormat(context).format(Date(note.expiresAtEpochMs)) +
                    " " + DateFormat.getTimeFormat(context).format(Date(note.expiresAtEpochMs))
            }
            Text(
                stringResource(R.string.result_expires_line, expiresText),
                style = MaterialTheme.typography.bodySmall,
                color = BurnPonyTheme.dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PassphraseReminder() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BurnPonyTheme.panel, RoundedCornerShape(12.dp))
            .border(1.dp, BurnPonyTheme.ember.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Key, null, tint = BurnPonyTheme.ember)
        Text(
            stringResource(R.string.result_passphrase_reminder),
            color = BurnPonyTheme.ink,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LinkCard(note: CreatedNote) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BurnPonyTheme.panel, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            note.shareLink,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = BurnPonyTheme.ink,
            maxLines = 3,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(BurnPonyTheme.fieldBackground, RoundedCornerShape(10.dp))
                .padding(12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // System share sheet
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(BurnPonyTheme.emberGradient, RoundedCornerShape(10.dp))
                    .clickable {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, note.shareLink)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.Share, null, tint = BurnPonyTheme.buttonInk, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.result_share),
                        color = BurnPonyTheme.buttonInk,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Copy with confirmation
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, BurnPonyTheme.line, RoundedCornerShape(10.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(note.shareLink))
                        copied = true
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        null,
                        tint = BurnPonyTheme.dim,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(if (copied) R.string.result_copied else R.string.result_copy),
                        color = BurnPonyTheme.dim,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCard(note: CreatedNote) {
    val qr = remember(note.shareLink) { QrCode.generate(note.shareLink) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BurnPonyTheme.panel, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.result_qr_caption),
                modifier = Modifier
                    .size(220.dp)
                    .background(BurnPonyTheme.ink, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .background(BurnPonyTheme.fieldBackground, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.result_qr_unavailable), color = BurnPonyTheme.dim)
            }
        }
        Text(
            stringResource(R.string.result_qr_caption),
            style = MaterialTheme.typography.bodySmall,
            color = BurnPonyTheme.dim,
            textAlign = TextAlign.Center,
        )
    }
}
