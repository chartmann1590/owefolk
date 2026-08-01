package com.charles.owefolk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.charles.owefolk.data.OwefolkRepository
import com.charles.owefolk.domain.Dashboard
import com.charles.owefolk.domain.NewExpense
import com.charles.owefolk.data.FirebaseOwefolkRepository
import com.charles.owefolk.observability.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppUiState(
    val dashboard: Dashboard? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class AppViewModel(private val repository: OwefolkRepository) : ViewModel() {
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    val uiState: StateFlow<AppUiState> = combine(repository.dashboard, working, message) { dashboard, busy, text ->
        AppUiState(dashboard, busy, text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun addExpense(expense: NewExpense, onDone: () -> Unit) = action("Expense added", onDone, "expense_created",
        mapOf("split_mode" to expense.splitMode.name.lowercase(), "participant_count" to expense.participantIds.size)) { repository.addExpense(expense) }
    fun createGroup(name: String, emoji: String, currency: String, onDone: () -> Unit) = action("Group created", onDone, "group_created") { repository.createGroup(name, emoji, currency) }
    fun createInvite(groupId: String, onReady: (String) -> Unit) = action("Invite ready", event = "invite_shared") { onReady(repository.createInvite(groupId)) }
    fun startSettlement(groupId: String, recipientId: String, amount: Long, provider: com.charles.owefolk.domain.PaymentProvider) =
        action("Payment marked sent — waiting for confirmation", event = "settlement_started", parameters = mapOf("provider" to provider.name.lowercase())) { repository.startSettlement(groupId, recipientId, amount, provider) }
    fun confirmSettlement(id: String) = action("Payment confirmed", event = "settlement_confirmed") { repository.confirmSettlement(id) }
    fun rejectSettlement(id: String) = action("Payment marked for review", event = "settlement_rejected") { repository.rejectSettlement(id) }
    fun sendReminder(groupId: String) = action("Friendly reminder sent", event = "reminder_sent") { repository.sendReminder(groupId) }
    fun updatePreferredProvider(provider: com.charles.owefolk.domain.PaymentProvider) =
        action("Preferred payment method updated", event = "payment_preference_updated",
            parameters = mapOf("provider" to provider.name.lowercase())) { repository.updatePreferredProvider(provider) }
    fun clearMessage() { message.value = null }
    fun deleteAccount() = action("Account deleted", event = "account_deleted") { repository.deleteAccount() }

    private fun action(success: String, onDone: () -> Unit = {}, event: String? = null, parameters: Map<String, Any> = emptyMap(), block: suspend () -> Unit) {
        viewModelScope.launch {
            working.value = true
            runCatching { block() }
                .onSuccess { message.value = success; event?.let { name -> Telemetry.event(name, parameters) }; onDone() }
                .onFailure { message.value = it.message ?: "Something went wrong"; Telemetry.record(it, event ?: "operation") }
            working.value = false
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository: OwefolkRepository = FirebaseOwefolkRepository()
                return AppViewModel(repository) as T
            }
        }
    }
}
