package com.charles.owefolk.data

import com.charles.owefolk.domain.Dashboard
import com.charles.owefolk.domain.NewExpense
import com.charles.owefolk.domain.PaymentProvider
import kotlinx.coroutines.flow.Flow

interface OwefolkRepository {
    val dashboard: Flow<Dashboard>
    suspend fun createGroup(name: String, emoji: String, currencyCode: String): String
    suspend fun createInvite(groupId: String): String
    suspend fun acceptInvite(groupId: String, token: String)
    suspend fun addExpense(expense: NewExpense)
    suspend fun startSettlement(groupId: String, recipientId: String, amountMinorUnits: Long, provider: PaymentProvider)
    suspend fun confirmSettlement(settlementId: String)
    suspend fun rejectSettlement(settlementId: String)
    suspend fun sendReminder(groupId: String)
    suspend fun updateRepaymentMode(groupId: String, simplifyDebts: Boolean)
    suspend fun updatePaymentPreference(provider: PaymentProvider, paymentHandle: String?)
    suspend fun deleteAccount()
}
