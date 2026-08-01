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

private enum class RootDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    GROUPS("groups", "Groups", Icons.Default.Groups),
    ACTIVITY("activity", "Activity", Icons.Default.Notifications),
    PROFILE("profile", "Profile", Icons.Default.Person),
}

@Composable
fun OwefolkApp(viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)) {
    var signedIn by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }
    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { signedIn = it.currentUser != null }
        FirebaseAuth.getInstance().addAuthStateListener(listener)
        onDispose { FirebaseAuth.getInstance().removeAuthStateListener(listener) }
    }
    if (!signedIn) {
        AuthScreen()
        return
    }
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val dashboard = state.dashboard
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddExpense by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<Group?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    if (dashboard == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
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
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
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
                        icon = { Icon(destination.icon, null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == RootDestination.HOME.route || currentRoute == RootDestination.GROUPS.route) {
                ExtendedFloatingActionButton(
                    onClick = { showAddExpense = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add expense") },
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
                ProfileScreen(dashboard.user, onProviderChange = viewModel::updatePreferredProvider,
                    onSignOut = { FirebaseAuth.getInstance().signOut() },
                    onDeleteAccount = viewModel::deleteAccount)
            }
        }
    }

    if (showAddExpense) {
        AddExpenseSheet(dashboard.groups, state.busy, onDismiss = { showAddExpense = false }) {
            viewModel.addExpense(it) { showAddExpense = false }
        }
    }
    if (showCreateGroup) {
        CreateGroupDialog(state.busy, onDismiss = { showCreateGroup = false }) { name, emoji, currency ->
            viewModel.createGroup(name, emoji, currency) { showCreateGroup = false }
        }
    }
    selectedGroup?.let { group ->
        GroupDetailSheet(
            group, dashboard.user, onDismiss = { selectedGroup = null }, onReminder = { viewModel.sendReminder(group.id) },
            onInvite = {
                viewModel.createInvite(group.id) { url ->
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Join my ${group.name} group on Owefolk: $url") }, "Invite friends"))
                }
            },
            onPaymentSent = { recipientId, provider -> viewModel.startSettlement(group.id, recipientId, -group.netMinorUnits, provider) },
        )
    }
}
