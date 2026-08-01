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
}
