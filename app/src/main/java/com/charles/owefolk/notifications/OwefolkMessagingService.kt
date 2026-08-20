package com.charles.owefolk.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.charles.owefolk.MainActivity
import com.charles.owefolk.R
import com.charles.owefolk.translate.TranslationManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class OwefolkMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val inviteUri = message.data["link"]?.let { android.net.Uri.parse(it) }
        val validUri = inviteUri?.let { uri ->
            val isWebInvite = uri.toString().startsWith("https://chartmann1590.github.io/owefolk/invite.html")
            val isDeepLink = uri.scheme == "owefolk" && uri.host == "invite"
            if (isWebInvite || isDeepLink) uri else null
        }
        val intent = Intent().apply {
            component = ComponentName(this@OwefolkMessagingService, MainActivity::class.java)
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            validUri?.let { putExtra(EXTRA_INVITE_URI, it.toString()) }
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = message.notification?.title ?: getString(R.string.notification_fallback_title)
        val body = message.notification?.body ?: getString(R.string.notification_fallback_body)
        TranslationManager.translateAsync(title) { translatedTitle ->
            TranslationManager.translateAsync(body) { translatedBody ->
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_owefolk)
                    .setContentTitle(translatedTitle)
                    .setContentText(translatedBody)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
                manager.notify(message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "owefolk_updates"
        const val EXTRA_INVITE_URI = "com.charles.owefolk.EXTRA_INVITE_URI"
    }
}
