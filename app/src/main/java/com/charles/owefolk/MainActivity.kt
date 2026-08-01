package com.charles.owefolk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.charles.owefolk.ui.OwefolkApp
import com.charles.owefolk.ui.theme.OwefolkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.data?.takeIf { it.scheme == "owefolk" && it.host == "invite" }?.let { invite ->
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
