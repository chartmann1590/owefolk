package com.charles.owefolk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.charles.owefolk.domain.Group
import com.google.firebase.auth.FirebaseAuth
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import com.charles.owefolk.ads.AdMobBanner
import com.charles.owefolk.ads.AdsManager
import com.charles.owefolk.data.FirebaseOwefolkRepository
import com.charles.owefolk.observability.Telemetry
import android.content.Context
import com.charles.owefolk.R
import com.charles.owefolk.translate.LanguagePickerUi
import com.charles.owefolk.translate.LocalTranslation
import com.charles.owefolk.translate.TranslatedText
import com.charles.owefolk.translate.TranslatedTextResource
import com.charles.owefolk.translate.TranslationController
import com.charles.owefolk.translate.TranslationDisclaimerBar
import com.charles.owefolk.translate.TranslationManager
import com.charles.owefolk.translate.dismissTranslationDisclaimer
import com.charles.owefolk.translate.isTranslationDisclaimerDismissed
import kotlinx.coroutines.launch

private enum class RootDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    GROUPS("groups", "Groups", Icons.Default.Groups),
    ACTIVITY("activity", "Activity", Icons.Default.Notifications),
    PROFILE("profile", "Profile", Icons.Default.Person),
}

private const val PRIVACY_ROUTE = "privacy"

@Composable
fun OwefolkApp(viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory(FirebaseOwefolkRepository()))) {
    val controller = remember { TranslationController(TranslationManager.state, TranslationManager::translate) }
    CompositionLocalProvider(LocalTranslation provides controller) {
        OwefolkAppContent(viewModel)
    }
}

@Composable
private fun OwefolkAppContent(viewModel: AppViewModel) {
    var signedIn by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }
    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { signedIn = it.currentUser != null }
        FirebaseAuth.getInstance().addAuthStateListener(listener)
        onDispose { FirebaseAuth.getInstance().removeAuthStateListener(listener) }
    }
    val context = LocalContext.current
    var showConsent by remember { mutableStateOf(false) }
    val appScope = rememberCoroutineScope()
    val translationState by TranslationManager.state.collectAsState()
    var disclaimerDismissed by remember { mutableStateOf(isTranslationDisclaimerDismissed(context)) }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)
        val seen = prefs.getBoolean("consent_seen", false)
        if (!seen) showConsent = true
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            icon = { Icon(Icons.Default.Shield, null) },
            title = { TranslatedTextResource(R.string.consent_title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TranslatedTextResource(R.string.consent_text)
                    TranslatedTextResource(R.string.consent_no_personal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TranslatedTextResource(R.string.consent_change_anytime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    Telemetry.setCollectionEnabled(context, true)
                    context.getSharedPreferences("telemetry", Context.MODE_PRIVATE).edit().putBoolean("consent_seen", true).apply()
                    showConsent = false
                }) { TranslatedTextResource(R.string.consent_allow) }
            },
            dismissButton = {
                TextButton(onClick = {
                    Telemetry.setCollectionEnabled(context, false)
                    context.getSharedPreferences("telemetry", Context.MODE_PRIVATE).edit().putBoolean("consent_seen", true).apply()
                    showConsent = false
                }) { TranslatedTextResource(R.string.consent_decline) }
            },
        )
    }

    if (!signedIn) {
        AuthScreen()
        return
    }
    val state by viewModel.uiState.collectAsState()
    val dashboard = state.dashboard
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddExpense by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<Group?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(TranslationManager.translate(it) ?: it)
            viewModel.clearMessage()
        }
    }

    if (dashboard == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (dashboard.needsOnboarding) {
        OnboardingScreen(
            busy = state.busy,
            message = state.message,
            onClearMessage = viewModel::clearMessage,
            onSaveNameDone = { name, color -> viewModel.saveProfileName(name, color) },
            onPaymentDone = viewModel::updatePaymentPreference,
            onCreateGroup = viewModel::createGroup,
            onAcceptInvite = viewModel::acceptInvite,
            onFinished = { viewModel.completeOnboarding() },
        )
        return
    }

    LaunchedEffect(dashboard.user.id) {
        val invitePreferences = context.getSharedPreferences("invites", android.content.Context.MODE_PRIVATE)
        val token = invitePreferences.getString("token", null)
        val groupId = invitePreferences.getString("group", null)
        if (token != null && groupId != null) {
            viewModel.acceptInvite(groupId, token) { invitePreferences.edit().clear().apply() }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val privacyOptionsRequired by AdsManager.privacyOptionsRequired.collectAsState()
    val premiumState by com.charles.owefolk.premium.PremiumManager.state.collectAsState()
    val isPremium = premiumState.isSubscribed || dashboard.user.premiumActive
    val showTranslationBar = translationState.active && !disclaimerDismissed
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showTranslationBar) {
                TranslationDisclaimerBar(onDismiss = { disclaimerDismissed = true; dismissTranslationDisclaimer(context) })
            }
        },
        bottomBar = {
            Column {
                if (currentRoute in RootDestination.entries.map { it.route } && !isPremium) AdMobBanner()
                if (currentRoute in RootDestination.entries.map { it.route }) {
                    NavigationBar(tonalElevation = 0.dp) {
                        RootDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(destination.icon, destination.label) },
                                label = { TranslatedText(destination.label) },
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == RootDestination.HOME.route || currentRoute == RootDestination.GROUPS.route) {
                ExtendedFloatingActionButton(
                    onClick = { showAddExpense = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { TranslatedTextResource(R.string.add_expense_save) },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                )
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = RootDestination.HOME.route, Modifier.padding(padding)) {
            composable(RootDestination.HOME.route) {
                HomeScreen(dashboard, onGroupClick = { selectedGroup = it },
                    onConfirm = viewModel::confirmSettlement, onReject = viewModel::rejectSettlement)
            }
            composable(RootDestination.GROUPS.route) {
                GroupsScreen(dashboard.groups, onGroupClick = { selectedGroup = it }, onReminder = viewModel::sendReminder,
                    onCreateGroup = { showCreateGroup = true })
            }
            composable(RootDestination.ACTIVITY.route) { ActivityScreen(dashboard.activities) }
            composable(RootDestination.PROFILE.route) {
                ProfileScreen(dashboard.user, onPaymentPreferenceChange = viewModel::updatePaymentPreference,
                    onSignOut = { FirebaseAuth.getInstance().signOut() },
                    onDeleteAccount = viewModel::deleteAccount,
                    showAdPrivacyOptions = privacyOptionsRequired,
                    onAdPrivacyOptions = { (context as? Activity)?.let(AdsManager::showPrivacyOptions) },
                    onOpenPrivacyPolicy = { navController.navigate(PRIVACY_ROUTE) },
                    onLanguageSettings = { showLanguagePicker = true },
                    premiumState = premiumState,
                    onUpgradePremium = { (context as? Activity)?.let(com.charles.owefolk.premium.PremiumManager::launch) },
                    onRestorePremium = { com.charles.owefolk.premium.PremiumManager.refreshSubscriptions() })
            }
            composable(PRIVACY_ROUTE) {
                PrivacyPolicyScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    if (showLanguagePicker) {
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { TranslatedTextResource(R.string.language_dialog_title) },
            text = {
                LanguagePickerUi(showDisclaimer = true)
            },
            confirmButton = {
                TextButton(onClick = { showLanguagePicker = false; disclaimerDismissed = false }) { TranslatedTextResource(R.string.translation_dismiss) }
            },
        )
    }
    if (showAddExpense) {
        AddExpenseSheet(dashboard.groups, state.busy, onDismiss = { showAddExpense = false }) {
            viewModel.addExpense(it) {
                showAddExpense = false
                if (!isPremium) (context as? Activity)?.let(AdsManager::onExpenseSaved)
            }
        }
    }
    if (showCreateGroup) {
        CreateGroupDialog(state.busy, onDismiss = { showCreateGroup = false }) { name, emoji, currency ->
            viewModel.createGroup(name, emoji, currency) { showCreateGroup = false }
        }
    }
    selectedGroup?.let { selected ->
        val group = dashboard.groups.firstOrNull { it.id == selected.id } ?: selected
        GroupDetailSheet(
            group, dashboard.user, onDismiss = { selectedGroup = null }, onReminder = { viewModel.sendReminder(group.id) },
            onInvite = {
                viewModel.createInvite(group.id) { url ->
                    appScope.launch {
                        val source = context.getString(R.string.invite_text, group.name, url)
                        val text = TranslationManager.translate(source) ?: source
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, context.getString(R.string.group_detail_invite)))
                    }
                }
            },
            onRepaymentModeChange = { simplify -> viewModel.updateRepaymentMode(group.id, simplify) },
            onPaymentSent = { recipientId, amount, provider -> viewModel.startSettlement(group.id, recipientId, amount, provider) },
        )
    }
}