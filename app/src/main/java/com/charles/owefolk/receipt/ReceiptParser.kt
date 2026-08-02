package com.charles.owefolk.receipt

import java.math.BigDecimal
import java.math.RoundingMode

data class ReceiptSuggestion(
    val merchant: String?,
    val totalMinorUnits: Long?,
    val recognizedLineCount: Int,
)

object ReceiptParser {
    private val amountPattern = Regex("(?<![\\d.])(?:[$€£]\\s*)?(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*[.,]\\s*(\\d{2})(?!\\d)")
    private val strongTotal = Regex("(?i)\\b(grand\\s+total|amount\\s+due|balance\\s+due|total\\s+due)\\b")
    private val normalTotal = Regex("(?i)\\btotal\\b")
    private val excludedTotal = Regex("(?i)\\b(subtotal|sub[- ]?total|tax|tip|gratuity|change|cash|tender|savings?)\\b")
    private val merchantNoise = Regex("(?i)\\b(receipt|invoice|order|transaction|cashier|register|terminal|date|time|tel|phone|www\\.|https?://|thank|welcome)\\b")
    private val dateOrPhone = Regex("(?:\\d{1,4}[-/.]){1,2}\\d{1,4}|\\(?\\d{3}\\)?[- .]\\d{3}[- .]\\d{4}")

    fun parse(text: String): ReceiptSuggestion {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val candidates = lines.flatMapIndexed { index, line ->
            amounts(line).map { amount ->
                val score = when {
                    excludedTotal.containsMatchIn(line) -> -100
                    strongTotal.containsMatchIn(line) -> 100
                    normalTotal.containsMatchIn(line) -> 80
                    else -> 0
                }
                AmountCandidate(amount, score, index)
            }
        }.filter { it.score >= 0 && it.minorUnits in 1..100_000_000 }

        val labeled = candidates.filter { it.score > 0 }
        val total = if (labeled.isNotEmpty()) {
            labeled.maxWithOrNull(compareBy<AmountCandidate> { it.score }.thenBy { it.lineIndex })
        } else {
            candidates.maxByOrNull(AmountCandidate::minorUnits)
        }?.minorUnits

        return ReceiptSuggestion(
            merchant = lines.take(10).firstOrNull(::looksLikeMerchant)?.take(80),
            totalMinorUnits = total,
            recognizedLineCount = lines.size,
        )
    }

    private fun looksLikeMerchant(line: String): Boolean =
        line.length in 2..80 && line.any(Char::isLetter) &&
            !merchantNoise.containsMatchIn(line) && !dateOrPhone.containsMatchIn(line) &&
            amountPattern.find(line) == null && line.count(Char::isDigit) <= line.length / 3

    private fun amounts(line: String): List<Long> = amountPattern.findAll(line).mapNotNull { match ->
        val normalized = "${match.groupValues[1].replace(",", "")}.${match.groupValues[2]}"
        runCatching {
            BigDecimal(normalized).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        }.getOrNull()
    }.toList()

    private data class AmountCandidate(val minorUnits: Long, val score: Int, val lineIndex: Int)
}
