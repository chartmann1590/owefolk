package com.charles.owefolk.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charles.owefolk.domain.*
import com.charles.owefolk.observability.Telemetry
import com.charles.owefolk.receipt.ReceiptScanner
import com.charles.owefolk.ui.theme.Coral
import com.charles.owefolk.ui.theme.Indigo
import com.charles.owefolk.ui.theme.Mint
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    dashboard: Dashboard,
    onGroupClick: (Group) -> Unit,
    onConfirm: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hey, ${dashboard.user.name} 👋", style = MaterialTheme.typography.headlineMedium)
                    Text("Here’s where things stand.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Avatar(dashboard.user, 48.dp)
            }
        }
        item { BalanceHero(dashboard) }
        val pending = dashboard.settlements.filter { it.status == SettlementStatus.SENT && it.recipient.id == dashboard.user.id }
        if (pending.isNotEmpty()) {
            item { SectionTitle("Needs your confirmation", "${pending.size} pending") }
            items(pending, key = Settlement::id) { settlement ->
                SettlementConfirmation(settlement, onConfirm, onReject)
            }
        }
        item { SectionTitle("Your groups", "See all") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
                items(dashboard.groups, key = Group::id) { group -> GroupCard(group, onGroupClick) }
            }
        }
        item { SectionTitle("Recent activity") }
        items(dashboard.activities.take(4), key = ActivityItem::id) { ActivityRow(it) }
    }
}

@Composable
private fun BalanceHero(dashboard: Dashboard) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Net balance ${Money(dashboard.netMinorUnits).formatted()}" },
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(Indigo, Color(0xFF7867EA), Coral))).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Your net balance", color = Color.White.copy(alpha = .82f), style = MaterialTheme.typography.labelLarge)
            Text(
                (if (dashboard.netMinorUnits >= 0) "+" else "−") + Money(kotlin.math.abs(dashboard.netMinorUnits)).formatted(),
                color = Color.White, style = MaterialTheme.typography.displaySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BalancePill("You’re owed", dashboard.owedToYouMinorUnits, Icons.Default.SouthWest, Modifier.weight(1f))
                BalancePill("You owe", dashboard.youOweMinorUnits, Icons.Default.NorthEast, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BalancePill(label: String, amount: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .15f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall)
            Text(Money(amount).formatted(), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettlementConfirmation(settlement: Settlement, onConfirm: (String) -> Unit, onReject: (String) -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(settlement.payer, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${settlement.payer.name} marked a payment sent", fontWeight = FontWeight.SemiBold)
                    Text(providerLabel(settlement.provider), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(settlement.amount.formatted(), style = MaterialTheme.typography.titleLarge, color = Mint)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onReject(settlement.id) }, Modifier.weight(1f)) { Text("Not received") }
                Button(onClick = { onConfirm(settlement.id) }, Modifier.weight(1f)) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("Confirm") }
            }
        }
    }
}

@Composable
private fun GroupCard(group: Group, onClick: (Group) -> Unit) {
    ElevatedCard(onClick = { onClick(group) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.width(220.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.emoji, fontSize = 30.sp, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(8.dp))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${group.members.size} people • ${group.currencyCode}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                when { group.netMinorUnits > 0 -> "You’re owed ${Money(group.netMinorUnits, group.currencyCode).formatted()}"; group.netMinorUnits < 0 -> "You owe ${Money(-group.netMinorUnits, group.currencyCode).formatted()}"; else -> "All settled up" },
                color = if (group.netMinorUnits >= 0) Mint else Coral, fontWeight = FontWeight.SemiBold,
            )
            AvatarStack(group.members)
        }
    }
}

@Composable
fun GroupsScreen(groups: List<Group>, onGroupClick: (Group) -> Unit, onReminder: (String) -> Unit, onCreateGroup: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Groups", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onCreateGroup) { Icon(Icons.Default.GroupAdd, null); Spacer(Modifier.width(6.dp)); Text("New") }
            }
        }
        item { Text("Shared tabs, without the awkward math.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(groups, key = Group::id) { group ->
            ElevatedCard(onClick = { onGroupClick(group) }, shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.emoji, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text("${group.members.size} members", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (group.netMinorUnits >= 0) "+${Money(group.netMinorUnits).formatted()}" else "−${Money(-group.netMinorUnits).formatted()}",
                            color = if (group.netMinorUnits >= 0) Mint else Coral, fontWeight = FontWeight.Bold,
                        )
                    }
                    IconButton(onClick = { onReminder(group.id) }) { Icon(Icons.Default.NotificationsActive, "Send reminder") }
                }
            }
        }
    }
}

@Composable
fun ActivityScreen(activities: List<ActivityItem>) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 80.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Activity", style = MaterialTheme.typography.headlineLarge) }
        item { Text("A clear history of every shared tab.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp)) }
        items(activities, key = ActivityItem::id) { ActivityRow(it) }
    }
}

@Composable
fun ProfileScreen(
    user: Person,
    onProviderChange: (PaymentProvider) -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    showAdPrivacyOptions: Boolean,
    onAdPrivacyOptions: () -> Unit,
) {
    val context = LocalContext.current
    var analyticsEnabled by remember { mutableStateOf(com.charles.owefolk.observability.Telemetry.isCollectionEnabled(context)) }
    var confirmDeletion by remember { mutableStateOf(false) }
    var chooseProvider by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Profile", style = MaterialTheme.typography.headlineLarge) }
        item {
            ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user, 64.dp); Spacer(Modifier.width(16.dp))
                    Column { Text(user.name, style = MaterialTheme.typography.titleLarge); Text("Signed in securely", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { SectionTitle("Getting paid") }
        item {
            SettingsRow(Icons.Default.Payments, "Preferred payment method", providerLabel(user.preferredProvider)) {
                chooseProvider = true
            }
        }
        item { SectionTitle("Privacy") }
        item {
            SettingsSwitch(Icons.Default.Analytics, "Privacy-safe diagnostics", "Crash and usage collection", analyticsEnabled) {
                analyticsEnabled = it
                com.charles.owefolk.observability.Telemetry.setCollectionEnabled(context, it)
            }
        }
        item { SettingsRow(Icons.Default.Shield, "Privacy policy", "How Owefolk protects your data") { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://chartmann1590.github.io/owefolk/privacy.html"))) } }
        if (showAdPrivacyOptions) item { SettingsRow(Icons.Default.PrivacyTip, "Ad privacy choices", "Review advertising consent", onClick = onAdPrivacyOptions) }
        item { SettingsRow(Icons.AutoMirrored.Filled.Logout, "Sign out", "Keep your shared ledger in Firebase", onClick = onSignOut) }
        item { SettingsRow(Icons.Default.DeleteOutline, "Delete account", "Remove your account and personal data", destructive = true, onClick = { confirmDeletion = true }) }
    }
    if (chooseProvider) AlertDialog(
        onDismissRequest = { chooseProvider = false },
        icon = { Icon(Icons.Default.Payments, null) },
        title = { Text("Preferred payment method") },
        text = {
            Column {
                PaymentProvider.entries.forEach { provider ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            chooseProvider = false
                            onProviderChange(provider)
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(provider == user.preferredProvider, null)
                        Spacer(Modifier.width(10.dp))
                        Text(providerLabel(provider))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { chooseProvider = false }) { Text("Cancel") } },
    )
    if (confirmDeletion) AlertDialog(
        onDismissRequest = { confirmDeletion = false }, icon = { Icon(Icons.Default.DeleteForever, null) },
        title = { Text("Delete your account?") },
        text = { Text("Your profile, sign-in, devices, and payment handles will be deleted. Shared ledger entries will remain as “Deleted member” so friends keep an accurate history.") },
        confirmButton = { TextButton(onClick = { confirmDeletion = false; onDeleteAccount() }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDeletion = false }) { Text("Keep account") } },
    )
}

@Composable
fun CreateGroupDialog(busy: Boolean, onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("👥") }
    var currency by remember { mutableStateOf("USD") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text(emoji, fontSize = 34.sp) },
        title = { Text("Create a group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Group name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(emoji, { emoji = it.take(8) }, Modifier.weight(.8f), label = { Text("Emoji") }, singleLine = true)
                    OutlinedTextField(currency, { currency = it.uppercase().filter(Char::isLetter).take(3) }, Modifier.weight(1.2f), label = { Text("Currency") }, singleLine = true)
                }
                Text("Currency is locked after the first expense.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onCreate(name, emoji.ifBlank { "👥" }, currency) }, enabled = name.isNotBlank() && currency.length == 3 && !busy) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(groups: List<Group>, busy: Boolean, onDismiss: () -> Unit, onSave: (NewExpense) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedGroup by remember { mutableStateOf(groups.firstOrNull()) }
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var scanningReceipt by remember { mutableStateOf(false) }
    var receiptStatus by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var mode by remember { mutableStateOf(SplitMode.EQUAL) }
    var selectedIds by remember(selectedGroup) { mutableStateOf(selectedGroup?.members?.map(Person::id)?.toSet().orEmpty()) }
    val valueInputs = remember(selectedGroup, mode) { mutableStateMapOf<String, String>() }
    val amountMinor = ((amountText.toDoubleOrNull() ?: 0.0) * 100).toLong()
    val exact = valueInputs.mapValues { ((it.value.toDoubleOrNull() ?: 0.0) * 100).toLong() }.filterKeys { it in selectedIds }
    val percentages = valueInputs.mapValues { ((it.value.toDoubleOrNull() ?: 0.0) * 100).toInt() }.filterKeys { it in selectedIds }
    val sharesValid = when (mode) {
        SplitMode.EQUAL -> true
        SplitMode.EXACT -> MoneyMath.validateExact(amountMinor, exact)
        SplitMode.PERCENT -> percentages.values.sum() == 10_000
    }
    val valid = selectedGroup != null && title.isNotBlank() && amountMinor > 0 && selectedIds.isNotEmpty() && sharesValid

    fun scanReceipt(uri: Uri) {
        scanningReceipt = true
        receiptStatus = null
        scope.launch {
            runCatching { ReceiptScanner.scan(context, uri) }
                .onSuccess { suggestion ->
                    suggestion.merchant?.let { title = it }
                    suggestion.totalMinorUnits?.let {
                        amountText = String.format(Locale.US, "%.2f", it / 100.0)
                    }
                    receiptStatus = when {
                        suggestion.recognizedLineCount == 0 -> "No text found. Try again in brighter light."
                        suggestion.merchant == null && suggestion.totalMinorUnits == null -> "Text found, but no merchant or total. Enter them below."
                        suggestion.totalMinorUnits == null -> "Merchant found. Check the receipt and enter the total."
                        else -> "Receipt scanned. Review the details before adding it."
                    }
                    Telemetry.event("receipt_scan_completed", mapOf("recognized_lines" to suggestion.recognizedLineCount))
                }
                .onFailure {
                    receiptStatus = "That receipt could not be read. Try a clearer photo."
                    Telemetry.record(it, "receipt_scan")
                }
            scanningReceipt = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        cameraUri = null
        if (saved && uri != null) scanReceipt(uri)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::scanReceipt)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        LazyColumn(
            Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(22.dp, 4.dp, 22.dp, 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text("Add an expense", style = MaterialTheme.typography.headlineMedium) }
            item {
                ElevatedCard(shape = RoundedCornerShape(22.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                if (scanningReceipt) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.DocumentScanner, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Scan a receipt", style = MaterialTheme.typography.titleMedium)
                                Text("On-device OCR fills the merchant and total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val uri = ReceiptScanner.newCameraUri(context)
                                    cameraUri = uri
                                    cameraLauncher.launch(uri)
                                },
                                enabled = !scanningReceipt,
                                modifier = Modifier.weight(1f),
                            ) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Camera") }
                            OutlinedButton(
                                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                enabled = !scanningReceipt,
                                modifier = Modifier.weight(1f),
                            ) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Photos") }
                        }
                        receiptStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups, key = Group::id) { group -> FilterChip(selected = selectedGroup?.id == group.id, onClick = { selectedGroup = group }, label = { Text("${group.emoji} ${group.name}") }) }
                }
            }
            item { OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("What was it for?") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, null) }, singleLine = true) }
            item {
                OutlinedTextField(amountText, { amountText = it.filter { char -> char.isDigit() || char == '.' } }, Modifier.fillMaxWidth(),
                    label = { Text("Amount") }, prefix = { Text("$") }, textStyle = MaterialTheme.typography.headlineMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SplitMode.entries.forEachIndexed { index, splitMode ->
                        SegmentedButton(selected = mode == splitMode, onClick = { mode = splitMode; valueInputs.clear() },
                            shape = SegmentedButtonDefaults.itemShape(index, SplitMode.entries.size)) { Text(splitMode.name.lowercase().replaceFirstChar(Char::uppercase)) }
                    }
                }
            }
            selectedGroup?.let { group ->
                item { Text("Split with", style = MaterialTheme.typography.titleMedium) }
                items(group.members, key = Person::id) { person ->
                    val selected = person.id in selectedIds
                    Row(Modifier.fillMaxWidth().clickable { selectedIds = if (selected) selectedIds - person.id else selectedIds + person.id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected, { checked -> selectedIds = if (checked) selectedIds + person.id else selectedIds - person.id })
                        Avatar(person, 38.dp); Spacer(Modifier.width(10.dp)); Text(person.name, Modifier.weight(1f))
                        AnimatedVisibility(selected && mode != SplitMode.EQUAL) {
                            OutlinedTextField(
                                value = valueInputs[person.id].orEmpty(), onValueChange = { valueInputs[person.id] = it.filter { char -> char.isDigit() || char == '.' } },
                                modifier = Modifier.width(108.dp), singleLine = true,
                                suffix = { Text(if (mode == SplitMode.PERCENT) "%" else "$") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                        }
                    }
                }
            }
            if (!sharesValid && amountMinor > 0 && mode != SplitMode.EQUAL) {
                item { Text(if (mode == SplitMode.EXACT) "Exact shares must equal ${Money(amountMinor).formatted()}." else "Percentages must total 100%.", color = MaterialTheme.colorScheme.error) }
            }
            item {
                Button(
                    enabled = valid && !busy,
                    onClick = {
                        onSave(NewExpense(selectedGroup!!.id, title, amountMinor, mode, selectedIds.toList(), exact, percentages))
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add expense") } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailSheet(
    group: Group,
    currentUser: Person,
    onDismiss: () -> Unit,
    onReminder: () -> Unit,
    onInvite: () -> Unit,
    onPaymentSent: (String, PaymentProvider) -> Unit,
) {
    val context = LocalContext.current
    var showPayOptions by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf<PaymentProvider?>(null) }
    val recipient = group.members.firstOrNull { it.id != currentUser.id }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(22.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.emoji, fontSize = 38.sp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(group.name, style = MaterialTheme.typography.headlineMedium); Text("${group.members.size} members • ${group.currencyCode}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (group.netMinorUnits >= 0) "You’re owed" else "You owe")
                    Text(Money(kotlin.math.abs(group.netMinorUnits), group.currencyCode).formatted(), style = MaterialTheme.typography.displaySmall)
                    Text(if (group.simplifyDebts) "Simplified repayments are on" else "Direct balances", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AvatarStack(group.members)
            if (group.netMinorUnits < 0) {
                Button({ showPayOptions = !showPayOptions }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Payments, null); Spacer(Modifier.width(8.dp)); Text("Settle up") }
                AnimatedVisibility(showPayOptions) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Continue with", style = MaterialTheme.typography.titleMedium)
                        PaymentProvider.entries.filter { it != PaymentProvider.CASH }.forEach { provider ->
                            OutlinedButton(
                                onClick = {
                                    if (recipient != null) {
                                        PaymentHandoff.launch(context, provider, recipient.paymentHandle, Money(-group.netMinorUnits), "Owefolk: ${group.name}")
                                        selectedProvider = provider
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("${providerLabel(provider)}${recipient?.let { " • ${it.name}" } ?: ""}") }
                        }
                        selectedProvider?.let { provider ->
                            Button(
                                onClick = { recipient?.let { onPaymentSent(it.id, provider) }; showPayOptions = false; selectedProvider = null },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text("I’ve sent it") }
                        }
                        Text("You’ll return to Owefolk and mark the payment sent. The recipient must confirm it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (group.netMinorUnits > 0) {
                OutlinedButton(onReminder, Modifier.fillMaxWidth()) { Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(8.dp)); Text("Send a friendly reminder") }
            }
            OutlinedButton(onInvite, Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Invite friends") }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val (icon, color) = when (item.kind) {
            ActivityKind.EXPENSE -> Icons.AutoMirrored.Filled.ReceiptLong to Indigo
            ActivityKind.PAYMENT -> Icons.Default.Payments to Mint
            ActivityKind.REMINDER -> Icons.Default.NotificationsActive to Coral
            ActivityKind.MEMBER -> Icons.Default.PersonAdd to Color(0xFFE8A33D)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.detail} • ${relativeTime(item.timestamp)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item.amount?.let { Text(it.formatted(), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SectionTitle(title: String, action: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun Avatar(person: Person, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(person.color)).semantics { contentDescription = person.name },
        contentAlignment = Alignment.Center,
    ) { Text(person.initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * .32f).sp) }
}

@Composable
private fun AvatarStack(people: List<Person>) {
    Row {
        people.take(4).forEachIndexed { index, person ->
            Box(Modifier.offset(x = (-8 * index).dp)) { Avatar(person, 32.dp) }
        }
        if (people.size > 4) Text("+${people.size - 4}", modifier = Modifier.offset(x = (-8 * 4).dp).align(Alignment.CenterVertically))
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, destructive: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SettingsSwitch(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChecked)
    }
}

private fun providerLabel(provider: PaymentProvider) = when (provider) {
    PaymentProvider.CASH_APP -> "Cash App"
    PaymentProvider.PAYPAL -> "PayPal.Me"
    PaymentProvider.VENMO -> "Venmo"
    PaymentProvider.ZELLE -> "Zelle"
    PaymentProvider.OTHER -> "Other payment link"
    PaymentProvider.CASH -> "Cash"
}

private fun relativeTime(timestamp: Instant): String {
    val duration = Duration.between(timestamp, Instant.now())
    return when {
        duration.toMinutes() < 2 -> "just now"
        duration.toHours() < 1 -> "${duration.toMinutes()}m"
        duration.toDays() < 1 -> "${duration.toHours()}h"
        else -> "${duration.toDays()}d"
    }
}
