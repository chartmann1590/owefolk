package com.charles.owefolk.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charles.owefolk.R
import com.charles.owefolk.domain.PaymentProvider
import com.charles.owefolk.translate.LanguagePickerUi
import com.charles.owefolk.translate.TranslatedText
import com.charles.owefolk.translate.TranslatedTextResource
import com.charles.owefolk.translate.rememberTranslatedRes
import com.charles.owefolk.ui.theme.Coral
import com.charles.owefolk.ui.theme.Indigo

private val AvatarPalette = listOf(
    0xFF5B4BD8,
    0xFFFF735C,
    0xFF1F9D82,
    0xFFE8A33D,
    0xFFE06BB0,
    0xFF3D8BE0,
    0xFF7C5CE0,
    0xFF3BB8D1,
)

private fun providerLabel(provider: PaymentProvider) = when (provider) {
    PaymentProvider.CASH_APP -> "Cash App"
    PaymentProvider.PAYPAL -> "PayPal.Me"
    PaymentProvider.VENMO -> "Venmo"
    PaymentProvider.ZELLE -> "Zelle"
    PaymentProvider.OTHER -> "Other payment link"
    PaymentProvider.CASH -> "Cash"
}

private fun paymentHandleLabel(provider: PaymentProvider) = when (provider) {
    PaymentProvider.CASH_APP -> "Cash App cashtag or link"
    PaymentProvider.PAYPAL -> "PayPal.Me name or link"
    PaymentProvider.VENMO -> "Venmo username"
    PaymentProvider.ZELLE -> "Zelle email or phone"
    PaymentProvider.OTHER -> "Secure payment link"
    PaymentProvider.CASH -> "No handle needed"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    busy: Boolean,
    message: String?,
    onClearMessage: () -> Unit,
    onSaveNameDone: (String, Long) -> Unit,
    onPaymentDone: (PaymentProvider, String?) -> Unit,
    onCreateGroup: (String, String, String, () -> Unit) -> Unit,
    onAcceptInvite: (String, String, () -> Unit) -> Unit,
    onFinished: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(AvatarPalette.first()) }
    var provider by remember { mutableStateOf(PaymentProvider.VENMO) }
    var handle by remember { mutableStateOf("") }
    var createdGroup by remember { mutableStateOf<String?>(null) }
    var joinedGroup by remember { mutableStateOf<String?>(null) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var joinLink by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(com.charles.owefolk.translate.TranslationManager.translate(it) ?: it)
            onClearMessage()
        }
    }

    val initials = remember(name) {
        name.split(' ').filter(String::isNotBlank).take(2)
            .joinToString("") { it.first().uppercase() }.ifBlank { "OF" }
    }
    val handleValid = provider == PaymentProvider.CASH ||
        (handle.isNotBlank() && (provider != PaymentProvider.OTHER || handle.startsWith("https://")))
    val nameValid = name.isNotBlank()
    val totalSteps = 4

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.size(76.dp).background(Brush.linearGradient(listOf(Indigo, Coral)), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PeopleAlt, null, tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TranslatedTextResource(R.string.onboard_title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        TranslatedTextResource(R.string.onboard_subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (step > 0) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                rememberTranslatedRes(R.string.onboard_step_label, step + 1, totalSteps),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { (step + 1) / totalSteps.toFloat() }, Modifier.fillMaxWidth().height(6.dp))
                        }
                    }

                    Card(shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (step) {
                                0 -> LanguageStep()
                                1 -> NameStep(name, { name = it.take(60) }, color, { color = it }, initials)
                                2 -> PaymentStep(provider, { provider = it }, handle, { handle = it.take(160) }, handleValid)
                                else -> GroupStep(
                                    createdGroup, joinedGroup, onOpenCreate = { showCreateGroup = true },
                                    joinLink, { joinLink = it }, joinError,
                                    onJoinClicked = {
                                        val trimmed = joinLink.trim()
                                        val token = runCatching { Uri.parse(trimmed).getQueryParameter("token") }.getOrNull()
                                        val groupId = runCatching { Uri.parse(trimmed).getQueryParameter("group") }.getOrNull()
                                        if (token.isNullOrBlank() || groupId.isNullOrBlank()) {
                                            joinError = true
                                        } else {
                                            joinError = false
                                            onAcceptInvite(groupId, token) { joinedGroup = groupId }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { step = (step - 1).coerceAtLeast(0) },
                        enabled = step > 0 && !busy,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); TranslatedTextResource(R.string.onboard_back) }
                    if (step < 3) {
                        Button(
                            onClick = {
                                if (step == 1 && nameValid) onSaveNameDone(name.trim(), color)
                                if (step == 2 && handleValid) onPaymentDone(provider, handle.takeIf { provider != PaymentProvider.CASH })
                                step += 1
                            },
                            enabled = (step == 1 && nameValid || step == 2 && handleValid || step == 0) && !busy,
                            modifier = Modifier.weight(1.6f).height(52.dp),
                        ) { TranslatedTextResource(R.string.onboard_continue, fontWeight = FontWeight.SemiBold) }
                    } else {
                        Button(
                            onClick = onFinished,
                            enabled = !busy,
                            modifier = Modifier.weight(1.6f).height(52.dp),
                        ) { TranslatedTextResource(R.string.onboard_finish, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (showCreateGroup) {
        CreateGroupDialog(busy, onDismiss = { showCreateGroup = false }) { gname, emoji, currency ->
            showCreateGroup = false
            onCreateGroup(gname, emoji, currency) { createdGroup = gname }
        }
    }
}

@Composable
private fun LanguageStep() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TranslatedTextResource(R.string.onboard_language_title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        TranslatedTextResource(R.string.onboard_language_help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LanguagePickerUi(showDisclaimer = true)
    }
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit, color: Long, onColor: (Long) -> Unit, initials: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TranslatedTextResource(R.string.onboard_name_title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start))
        Box(Modifier.size(84.dp).clip(CircleShape).background(Color(color)), contentAlignment = Alignment.Center) {
            Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 30.sp)
        }
        OutlinedTextField(
            name, onName, modifier = Modifier.fillMaxWidth(),
            label = { TranslatedTextResource(R.string.onboard_name_hint) }, singleLine = true,
        )
        TranslatedTextResource(R.string.onboard_name_help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarPalette.forEach { paletteColor ->
                val selected = paletteColor == color
                Box(
                    Modifier
                        .size(if (selected) 38.dp else 32.dp)
                        .clip(CircleShape)
                        .background(Color(paletteColor))
                        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        .clickable { onColor(paletteColor) },
                )
            }
        }
    }
}

@Composable
private fun PaymentStep(provider: PaymentProvider, onProvider: (PaymentProvider) -> Unit, handle: String, onHandle: (String) -> Unit, valid: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TranslatedTextResource(R.string.onboard_payment_title, style = MaterialTheme.typography.titleLarge)
        TranslatedTextResource(R.string.onboard_payment_help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PaymentProvider.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable { onProvider(option) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(provider == option, { onProvider(option) })
                Spacer(Modifier.width(8.dp))
                TranslatedText(providerLabel(option), fontWeight = if (provider == option) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        if (provider != PaymentProvider.CASH) {
            OutlinedTextField(
                handle, onHandle, modifier = Modifier.fillMaxWidth(),
                label = { TranslatedText(paymentHandleLabel(provider)) },
                supportingText = { TranslatedTextResource(R.string.payment_handle_share_note) },
                isError = handle.isNotBlank() && !valid,
                singleLine = true,
            )
        }
    }
}

@Composable
private fun GroupStep(
    createdGroup: String?,
    joinedGroup: String?,
    onOpenCreate: () -> Unit,
    joinLink: String,
    onJoinLink: (String) -> Unit,
    joinError: Boolean,
    onJoinClicked: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TranslatedTextResource(R.string.onboard_group_title, style = MaterialTheme.typography.titleLarge)
        TranslatedTextResource(R.string.onboard_group_help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onOpenCreate, Modifier.fillMaxWidth()) { TranslatedTextResource(R.string.onboard_group_create, fontWeight = FontWeight.SemiBold) }
        HorizontalDivider()
        OutlinedTextField(
            joinLink, onJoinLink, modifier = Modifier.fillMaxWidth(),
            label = { TranslatedTextResource(R.string.onboard_group_join_hint) },
            supportingText = { if (joinError) TranslatedTextResource(R.string.onboard_join_invalid, color = MaterialTheme.colorScheme.error) else TranslatedTextResource(R.string.onboard_group_join) },
            isError = joinError,
            singleLine = true,
        )
        OutlinedButton(onJoinClicked, Modifier.fillMaxWidth()) { TranslatedTextResource(R.string.onboard_group_join_button) }
        val done = createdGroup ?: joinedGroup
        if (done != null) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (createdGroup != null) rememberTranslatedRes(R.string.onboard_group_created, done) else rememberTranslatedRes(R.string.onboard_group_joined),
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}