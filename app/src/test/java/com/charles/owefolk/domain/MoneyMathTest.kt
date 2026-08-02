package com.charles.owefolk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyMathTest {
    @Test fun equalSplitAllocatesEveryCentDeterministically() {
        val result = MoneyMath.splitEqual(1_000, listOf("a", "b", "c"))
        assertEquals(mapOf("a" to 334L, "b" to 333L, "c" to 333L), result)
        assertEquals(1_000L, result.values.sum())
    }

    @Test fun percentSplitUsesLargestRemainder() {
        val result = MoneyMath.splitPercent(101, mapOf("a" to 3_333, "b" to 3_333, "c" to 3_334))
        assertEquals(101L, result.values.sum())
        assertEquals(34L, result["c"])
    }

    @Test fun exactSplitValidationRejectsMismatch() {
        assertTrue(MoneyMath.validateExact(500, mapOf("a" to 200, "b" to 300)))
        assertEquals(false, MoneyMath.validateExact(500, mapOf("a" to 200, "b" to 299)))
    }

    @Test fun simplifierBalancesGroupWithFewTransfers() {
        val transfers = DebtSimplifier.simplify(mapOf("alex" to -5_000, "bea" to -2_500, "charles" to 7_500))
        assertEquals(listOf(Transfer("alex", "charles", 5_000), Transfer("bea", "charles", 2_500)), transfers)
    }

    @Test fun directLedgerPreservesWhoOriginallyOwesWhom() {
        val transfers = LedgerMath.directTransfers(
            listOf(
                LedgerCharge("alex", mapOf("alex" to 1_000, "bea" to 1_000, "charles" to 1_000)),
                LedgerCharge("bea", mapOf("alex" to 500, "bea" to 500)),
            ),
            emptyList(),
        )
        assertEquals(listOf(Transfer("bea", "alex", 500), Transfer("charles", "alex", 1_000)), transfers)
    }

    @Test fun confirmedPaymentReducesOnlyTheMatchingDirectDebt() {
        val transfers = LedgerMath.directTransfers(
            listOf(LedgerCharge("alex", mapOf("bea" to 1_000, "charles" to 800))),
            listOf(LedgerPayment("bea", "alex", 400)),
        )
        assertEquals(listOf(Transfer("bea", "alex", 600), Transfer("charles", "alex", 800)), transfers)
    }

    @Test fun everyMemberGetsTheSameNetUnderDirectAndSimplifiedModes() {
        val direct = listOf(Transfer("alex", "bea", 500), Transfer("bea", "charles", 1_000))
        val net = LedgerMath.netByPerson(listOf("alex", "bea", "charles"), direct)
        val simplified = DebtSimplifier.simplify(net)
        assertEquals(net, LedgerMath.netByPerson(net.keys, simplified))
    }
}
