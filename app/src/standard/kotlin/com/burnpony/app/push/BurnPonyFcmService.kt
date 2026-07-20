//
// BurnPonyFcmService.kt (standard flavor)
// Receives FCM messages and token rotations.
//
// Diego round B5 (notification label rewrite): Android needs no service
// extension — the label is looked up in the local Room store by note_id and
// the body becomes “{label}” was opened; any unexpected condition falls back
// to the generic body. The server never knows any label.
//

package com.burnpony.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.burnpony.app.BurnPonyApp
import com.burnpony.app.MainActivity
import com.burnpony.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BurnPonyFcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // Re-register pending receipt notes with the fresh token; refreshAll
        // runs the same registration pass as a Sent Notes refresh.
        val container = (applicationContext as? BurnPonyApp)?.container ?: return
        scope.launch {
            runCatching { container.repository.refreshAll() }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val container = (applicationContext as? BurnPonyApp)?.container ?: return
        scope.launch {
            val noteId = message.data["note_id"]
            val label = noteId?.let {
                runCatching { container.repository.labelFor(it) }.getOrNull()
            }
            showNotification(message, label)
            runCatching { container.repository.refreshAll() }
        }
    }

    private fun showNotification(message: RemoteMessage, label: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(this)
        val title = message.notification?.title ?: getString(R.string.app_name)
        val body = if (label != null) {
            getString(R.string.push_note_opened_labeled, label)
        } else {
            message.notification?.body ?: getString(R.string.push_note_opened)
        }
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(message.data["note_id"].hashCode(), notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "receipts"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.push_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }
}
