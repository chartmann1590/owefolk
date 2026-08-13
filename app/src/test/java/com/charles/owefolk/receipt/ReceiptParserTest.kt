package com.charles.owefolk.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptParserTest {
    @Test fun `extracts merchant and amount due`() {
        val result = ReceiptParser.parse("""
            CORNER MARKET
            123 MAIN STREET
            SUBTOTAL 18.49
            TAX 1.48
            AMOUNT DUE $19.97
            THANK YOU
        """.trimIndent())

        assertEquals("CORNER MARKET", result.merchant)
        assertEquals(1_997L, result.totalMinorUnits)
    }

    @Test fun `prefers grand total over subtotal and tip`() {
        val result = ReceiptParser.parse("""
            NORTHSIDE CAFE
            Subtotal $42.00
            Tip $8.40
            Grand Total $50.40
        """.trimIndent())

        assertEquals("NORTHSIDE CAFE", result.merchant)
        assertEquals(5_040L, result.totalMinorUnits)
    }

    @Test fun `uses largest plausible amount when total label is missed`() {
        val result = ReceiptParser.parse("BAKERY HOUSE\nCroissant 4.25\nCoffee 3.50\n7.75")
        assertEquals(775L, result.totalMinorUnits)
    }

    @Test fun `does not invent an amount`() {
        val result = ReceiptParser.parse("FARMERS MARKET\nTHANK YOU")
        assertEquals("FARMERS MARKET", result.merchant)
        assertNull(result.totalMinorUnits)
    }

    @Test fun `parses single decimal digit amounts`() {
        val result = ReceiptParser.parse("STORE\nTotal $4.5")
        assertEquals(450L, result.totalMinorUnits)
    }

    @Test fun `parses euro symbol amounts`() {
        val result = ReceiptParser.parse("CAFE\nTotal €12.50")
        assertEquals(1_250L, result.totalMinorUnits)
    }

    @Test fun `parses comma decimal separator`() {
        val result = ReceiptParser.parse("BAKERY\nTotal 19,97")
        assertEquals(1_997L, result.totalMinorUnits)
    }

    @Test fun `ignores subtotal and tax lines`() {
        val result = ReceiptParser.parse("""
            GROCERY
            Subtotal 30.00
            Tax 2.40
            Total 32.40
        """.trimIndent())
        assertEquals(3_240L, result.totalMinorUnits)
    }
}
