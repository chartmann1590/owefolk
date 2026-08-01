package com.charles.owefolk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.charles.owefolk.ui.OwefolkApp
import com.charles.owefolk.ui.theme.OwefolkTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        completeEmailLinkIfPresent()
        setContent { OwefolkTheme { OwefolkApp() } }
    }

    private fun completeEmailLinkIfPresent() {
        val link = intent?.data?.toString() ?: return
        val auth = FirebaseAuth.getInstance()
        if (!auth.isSignInWithEmailLink(link)) return
        val email = getSharedPreferences("auth", MODE_PRIVATE).getString("pending_email", null) ?: return
        auth.signInWithEmailLink(email, link).addOnSuccessListener {
            getSharedPreferences("auth", MODE_PRIVATE).edit().remove("pending_email").apply()
            val user = it.user ?: return@addOnSuccessListener
            val name = user.displayName ?: email.substringBefore('@').replaceFirstChar(Char::uppercase)
            FirebaseFirestore.getInstance().collection("users").document(user.uid).set(
                mapOf("name" to name, "initials" to name.take(2).uppercase(), "email" to email,
                    "color" to 0xFF5B4BD8, "preferredProvider" to "VENMO", "createdAt" to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            )
        }
    }
}
