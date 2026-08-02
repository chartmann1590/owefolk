package com.charles.owefolk.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.charles.owefolk.ui.theme.Coral
import com.charles.owefolk.ui.theme.Indigo
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun AuthScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(92.dp).background(Brush.linearGradient(listOf(Indigo, Coral)), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PeopleAlt, null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Welcome to Owefolk", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text("Shared tabs, clear friendships.", color = Color.White.copy(alpha = .78f))
            Spacer(Modifier.height(34.dp))
            Card(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                message = runCatching { signInWithGoogle(context) }.fold({ null }, { it.message ?: "Google sign-in failed" })
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !busy,
                    ) { Text("Continue with Google", fontWeight = FontWeight.SemiBold) }
                    Row(verticalAlignment = Alignment.CenterVertically) { HorizontalDivider(Modifier.weight(1f)); Text("  or  ", color = MaterialTheme.colorScheme.onSurfaceVariant); HorizontalDivider(Modifier.weight(1f)) }
                    OutlinedTextField(
                        value = email, onValueChange = { email = it.trim() }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email address") }, leadingIcon = { Icon(Icons.Default.Email, null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true,
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    message = runCatching { createAccountWithEmail(email, password) }
                                        .fold({ null }, { it.message ?: "Account creation failed" })
                                    busy = false
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            enabled = email.contains('@') && password.length >= 8 && !busy,
                        ) { Text("Create account") }
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    message = runCatching { signInWithEmail(email, password) }
                                        .fold({ null }, { it.message ?: "Email sign-in failed" })
                                    busy = false
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            enabled = email.contains('@') && password.length >= 8 && !busy,
                        ) { Text("Sign in") }
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("By continuing, you agree to keep your groups kind and accurate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

private suspend fun signInWithGoogle(context: Context) {
    val resourceId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    require(resourceId != 0) { "Firebase OAuth client ID is missing" }
    val googleOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(context.getString(resourceId))
        .setAutoSelectEnabled(true)
        .build()
    val result = CredentialManager.create(context).getCredential(context, GetCredentialRequest.Builder().addCredentialOption(googleOption).build())
    val credential = result.credential
    require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Unexpected credential" }
    val google = GoogleIdTokenCredential.createFrom(credential.data)
    val authResult = FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
    createOrUpdateProfile(requireNotNull(authResult.user))
}

private suspend fun signInWithEmail(email: String, password: String) {
    val result = FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password).await()
    createOrUpdateProfile(requireNotNull(result.user))
}

private suspend fun createAccountWithEmail(email: String, password: String) {
    val result = FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password).await()
    createOrUpdateProfile(requireNotNull(result.user))
}

private suspend fun createOrUpdateProfile(user: com.google.firebase.auth.FirebaseUser) {
    val name = user.displayName ?: "Friend"
    val initials = name.split(' ').filter(String::isNotBlank).take(2).joinToString("") { it.first().uppercase() }.ifBlank { "OF" }
    val profile = FirebaseFirestore.getInstance().collection("users").document(user.uid)
    if (profile.get().await().exists()) {
        profile.set(mapOf("name" to name, "initials" to initials, "color" to 0xFF5B4BD8),
            com.google.firebase.firestore.SetOptions.merge()).await()
    } else {
        profile.set(mapOf("name" to name, "initials" to initials, "email" to user.email, "color" to 0xFF5B4BD8,
            "preferredProvider" to "VENMO", "createdAt" to FieldValue.serverTimestamp())).await()
    }
}
