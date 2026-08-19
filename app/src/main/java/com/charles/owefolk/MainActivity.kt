package com.charles.owefolk

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.charles.owefolk.ui.OwefolkApp
import com.charles.owefolk.ui.theme.OwefolkTheme
import com.charles.owefolk.ads.AdsManager
import com.charles.owefolk.notifications.OwefolkMessagingService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdsManager.initialize(this)
        com.charles.owefolk.premium.PremiumManager.start(this)
        enableEdgeToEdge()
        handleDeepLink(intent)
        requestNotificationPermission()
        setContent { OwefolkTheme { OwefolkApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val notificationLink = intent?.getStringExtra(OwefolkMessagingService.EXTRA_INVITE_URI)?.let(Uri::parse)
        val invite = intent?.data ?: notificationLink
        val isInviteLink = invite?.scheme == "owefolk" && invite.host == "invite" ||
            invite?.host == "chartmann1590.github.io" && invite.path?.startsWith("/owefolk/invite.html") == true
        invite?.takeIf { isInviteLink }?.let { uri ->
            val token = uri.getQueryParameter("token")
            val group = uri.getQueryParameter("group")
            if (!token.isNullOrBlank() && !group.isNullOrBlank()) {
                getSharedPreferences("invites", MODE_PRIVATE).edit()
                    .putString("token", token).putString("group", group).apply()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
