package com.charles.owefolk.domain

import kotlin.math.floor

object MoneyMath {
    fun splitEqual(totalMinorUnits: Long, participantIds: List<String>): Map<String, Long> {
        require(totalMinorUnits > 0) { "Total must be positive" }
        require(participantIds.isNotEmpty()) { "At least one participant is required" }
        require(participantIds.distinct().size == participantIds.size) { "Participants must be unique" }
        val base = totalMinorUnits / participantIds.size
        val remainder = (totalMinorUnits % participantIds.size).toInt()
        return participantIds.mapIndexed { index, id -> id to base + if (index < remainder) 1 else 0 }.toMap()
    }

    fun splitPercent(totalMinorUnits: Long, basisPoints: Map<String, Int>): Map<String, Long> {
        require(totalMinorUnits > 0) { "Total must be positive" }
        require(basisPoints.isNotEmpty()) { "At least one participant is required" }
        require(basisPoints.values.all { it >= 0 } && basisPoints.values.sum() == 10_000) {
            "Percentages must total 100%"
        }
        val raw = basisPoints.mapValues { (_, bps) -> totalMinorUnits * bps / 10_000.0 }
        val floorValues = raw.mapValues { floor(it.value).toLong() }.toMutableMap()
        var remaining = totalMinorUnits - floorValues.values.sum()
        raw.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value - floor(it.value) }.thenBy { it.key })
            .forEach { entry ->
                if (remaining > 0) {
                    floorValues[entry.key] = floorValues.getValue(entry.key) + 1
                    remaining--
                }
            }
        return floorValues
    }

    fun validateExact(totalMinorUnits: Long, shares: Map<String, Long>): Boolean =
        totalMinorUnits > 0 && shares.isNotEmpty() && shares.values.all { it >= 0 } && shares.values.sum() == totalMinorUnits
}

object DebtSimplifier {
    fun simplify(netByPerson: Map<String, Long>): List<Transfer> {
        require(netByPerson.values.sum() == 0L) { "Net balances must sum to zero" }
        val debtors = netByPerson.filterValues { it < 0 }.map { MutableBalance(it.key, -it.value) }.sortedBy { it.id }.toMutableList()
        val creditors = netByPerson.filterValues { it > 0 }.map { MutableBalance(it.key, it.value) }.sortedBy { it.id }.toMutableList()
        val result = mutableListOf<Transfer>()
        var debtorIndex = 0
        var creditorIndex = 0
        while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
            val debtor = debtors[debtorIndex]
            val creditor = creditors[creditorIndex]
            val amount = minOf(debtor.amount, creditor.amount)
            if (amount > 0) result += Transfer(debtor.id, creditor.id, amount)
            debtor.amount -= amount
            creditor.amount -= amount
            if (debtor.amount == 0L) debtorIndex++
            if (creditor.amount == 0L) creditorIndex++
        }
        return result
    }

    private data class MutableBalance(val id: String, var amount: Long)
}

data class LedgerCharge(val paidById: String, val sharesMinorUnits: Map<String, Long>)
data class LedgerPayment(val payerId: String, val recipientId: String, val amountMinorUnits: Long)

object LedgerMath {
    fun directTransfers(charges: List<LedgerCharge>, confirmedPayments: List<LedgerPayment>): List<Transfer> {
        val debts = mutableMapOf<Pair<String, String>, Long>()

        fun addDebt(fromId: String, toId: String, amount: Long) {
            if (fromId == toId || amount <= 0) return
            val reverse = toId to fromId
            val reverseAmount = debts[reverse] ?: 0L
            val cancelled = minOf(amount, reverseAmount)
            if (cancelled == reverseAmount) debts.remove(reverse) else debts[reverse] = reverseAmount - cancelled
            val remaining = amount - cancelled
            if (remaining > 0) debts[fromId to toId] = (debts[fromId to toId] ?: 0L) + remaining
        }

        charges.forEach { charge ->
            charge.sharesMinorUnits.forEach { (personId, amount) -> addDebt(personId, charge.paidById, amount) }
        }
        confirmedPayments.forEach { payment ->
            addDebt(payment.recipientId, payment.payerId, payment.amountMinorUnits)
        }
        return debts.entries
            .filter { it.value > 0 }
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { Transfer(it.key.first, it.key.second, it.value) }
    }

    fun netByPerson(memberIds: Collection<String>, transfers: List<Transfer>): Map<String, Long> {
        val result = memberIds.associateWith { 0L }.toMutableMap()
        transfers.forEach { transfer ->
            result[transfer.fromId] = (result[transfer.fromId] ?: 0L) - transfer.minorUnits
            result[transfer.toId] = (result[transfer.toId] ?: 0L) + transfer.minorUnits
        }
        return result
    }
}
