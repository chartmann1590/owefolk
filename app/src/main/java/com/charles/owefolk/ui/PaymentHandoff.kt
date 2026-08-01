package com.charles.owefolk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.charles.owefolk.domain.Money
import com.charles.owefolk.domain.PaymentProvider

object PaymentHandoff {
    fun launch(context: Context, provider: PaymentProvider, recipient: String?, amount: Money, note: String): Boolean {
        val decimalAmount = "%.2f".format(java.util.Locale.US, amount.minorUnits / 100.0)
        val handle = recipient?.trim()?.takeUnless(String::isBlank)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText(
                "Owefolk payment details",
                listOfNotNull(handle, amount.formatted(), note).joinToString(" • "),
            ),
        )
        val uri = when (provider) {
            PaymentProvider.PAYPAL -> handle
                ?.let { Uri.parse("https://paypal.me/${it.trimStart('@')}/$decimalAmount${amount.currencyCode}") }
                ?: Uri.parse("https://www.paypal.com/myaccount/transfer/homepage")
            PaymentProvider.CASH_APP -> handle?.takeIf { it.startsWith("https://cash.app/") }?.let(Uri::parse)
                ?: Uri.parse("https://cash.app/")
            PaymentProvider.VENMO -> handle?.let { Uri.parse("https://venmo.com/${it.trimStart('@')}") }
                ?: Uri.parse("https://venmo.com/")
            PaymentProvider.ZELLE -> Uri.parse("https://www.zellepay.com/")
            PaymentProvider.OTHER -> handle?.takeIf { it.startsWith("https://") }?.let(Uri::parse)
            PaymentProvider.CASH -> null
        } ?: return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
