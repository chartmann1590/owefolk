package com.charles.owefolk

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.charles.owefolk.ui.OwefolkApp
import com.charles.owefolk.ui.theme.OwefolkTheme
import com.charles.owefolk.ads.AdsManager
import com.charles.owefolk.notifications.OwefolkMessagingService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdsManager.initialize(this)
        enableEdgeToEdge()
        val notificationLink = intent?.getStringExtra(OwefolkMessagingService.EXTRA_INVITE_URI)?.let(Uri::parse)
        (intent?.data ?: notificationLink)?.takeIf { it.scheme == "owefolk" && it.host == "invite" }?.let { invite ->
            val token = invite.getQueryParameter("token")
            val group = invite.getQueryParameter("group")
            if (!token.isNullOrBlank() && !group.isNullOrBlank()) {
                getSharedPreferences("invites", MODE_PRIVATE).edit()
                    .putString("token", token).putString("group", group).apply()
            }
        }
        setContent { OwefolkTheme { OwefolkApp() } }
    }
}
