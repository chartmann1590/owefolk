package com.charles.owefolk.domain

import java.text.NumberFormat
import java.time.Instant
import java.util.Currency
import java.util.Locale

data class Money(val minorUnits: Long, val currencyCode: String = "USD") {
    fun formatted(locale: Locale = Locale.getDefault()): String = NumberFormat.getCurrencyInstance(locale).run {
        val targetCurrency = Currency.getInstance(currencyCode)
        currency = targetCurrency
        format(minorUnits.toDouble() / Math.pow(10.0, targetCurrency.defaultFractionDigits.toDouble()))
    }
}

data class Person(
    val id: String,
    val name: String,
    val initials: String,
    val color: Long,
    val preferredProvider: PaymentProvider = PaymentProvider.VENMO,
    val paymentHandle: String? = null,
)

data class Group(
    val id: String,
    val name: String,
    val emoji: String,
    val currencyCode: String,
    val members: List<Person>,
    val netMinorUnits: Long,
    val simplifyDebts: Boolean = true,
    val repayments: List<Repayment> = emptyList(),
)

data class Repayment(
    val from: Person,
    val to: Person,
    val amount: Money,
)

enum class SplitMode { EQUAL, EXACT, PERCENT }

data class SplitAllocation(
    val person: Person,
    val minorUnits: Long,
)

data class Expense(
    val id: String,
    val groupId: String,
    val title: String,
    val emoji: String,
    val total: Money,
    val paidBy: Person,
    val allocations: List<SplitAllocation>,
    val splitMode: SplitMode,
    val createdAt: Instant,
)

enum class SettlementStatus { DRAFT, SENT, CONFIRMED, REJECTED, CANCELLED }
enum class PaymentProvider { CASH_APP, VENMO, PAYPAL, ZELLE, OTHER, CASH }

data class Settlement(
    val id: String,
    val payer: Person,
    val recipient: Person,
    val amount: Money,
    val provider: PaymentProvider,
    val status: SettlementStatus,
    val createdAt: Instant,
)

enum class ActivityKind { EXPENSE, PAYMENT, REMINDER, MEMBER }

data class ActivityItem(
    val id: String,
    val kind: ActivityKind,
    val title: String,
    val detail: String,
    val timestamp: Instant,
    val amount: Money? = null,
)

data class Dashboard(
    val user: Person,
    val groups: List<Group>,
    val activities: List<ActivityItem>,
    val settlements: List<Settlement>,
) {
    val netMinorUnits: Long get() = groups.sumOf(Group::netMinorUnits)
    val owedToYouMinorUnits: Long get() = groups.sumOf { maxOf(it.netMinorUnits, 0) }
    val youOweMinorUnits: Long get() = groups.sumOf { maxOf(-it.netMinorUnits, 0) }
}

data class NewExpense(
    val groupId: String,
    val title: String,
    val totalMinorUnits: Long,
    val splitMode: SplitMode,
    val participantIds: List<String>,
    val exactSharesMinorUnits: Map<String, Long> = emptyMap(),
    val percentageBasisPoints: Map<String, Int> = emptyMap(),
)

data class Transfer(val fromId: String, val toId: String, val minorUnits: Long)
