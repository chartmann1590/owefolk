package com.charles.owefolk.premium

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.charles.owefolk.observability.Telemetry
import com.hartmannstudios.direct.DirectBilling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PremiumManager {

    const val PRODUCT_ID = "owefolk_premium_monthly"

    data class PremiumUiState(
        val isLoading: Boolean = true,
        val productPrice: String? = null,
        val isSubscribed: Boolean = false,
    )

    private val _state = MutableStateFlow(PremiumUiState())
    val state: StateFlow<PremiumUiState> = _state.asStateFlow()

    @Volatile
    private var billingClient: BillingClient? = null

    @Volatile
    private var productDetails: ProductDetails? = null

    // Stripe catalog key (shared Hartmann Studios billing worker, app "owefolk")
    const val DIRECT_MONTHLY = "owefolk_premium_monthly"
    private const val DIRECT_PRICE = "$2.99"
    @Volatile private var directStarted = false

    fun start(context: Context) {
        if (DirectBilling.isDirect) { startDirect(context); return }
        if (billingClient != null) return
        val client = BillingClient.newBuilder(context)
            .setListener(::onPurchasesUpdated)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .enableAutoServiceReconnection()
            .build()
        billingClient = client
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    loadProduct()
                    refreshSubscriptions()
                } else {
                    _state.value = _state.value.copy(isLoading = false)
                    Telemetry.event("billing_setup_failed", mapOf("code" to result.responseCode))
                }
            }

            override fun onBillingServiceDisconnected() {
                // BillingClient will retry automatically; entitlement refreshes on the next query.
            }
        })
    }

    // ── Direct (GitHub / charleshartmann.com) build: Stripe Checkout through DirectBilling instead of Google Play ──
    private fun startDirect(context: Context) {
        if (directStarted) return
        directStarted = true
        DirectBilling.init(context.applicationContext, "owefolk")
        DirectBilling.addListener { updateDirect() }
        updateDirect()
    }

    private fun updateDirect() {
        _state.value = _state.value.copy(isLoading = false, productPrice = DIRECT_PRICE, isSubscribed = DirectBilling.isEntitled(DIRECT_MONTHLY))
    }

    /** Restore: direct builds look the Stripe purchase up by email; Play builds re-query Google Play. */
    fun restore(activity: Activity?) {
        if (DirectBilling.isDirect && activity != null) DirectBilling.restore(activity) else refreshSubscriptions()
    }

    /** Direct builds: Stripe customer portal (cancel / update card). */
    fun manageSubscription(activity: Activity) {
        if (DirectBilling.isDirect) DirectBilling.manageSubscription(activity)
    }

    fun refreshSubscriptions() {
        if (DirectBilling.isDirect) { updateDirect(); return }
        val client = billingClient ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params, purchasesResponseListener)
    }

    fun isPremiumNow(): Boolean = _state.value.isSubscribed

    fun launch(activity: Activity) {
        if (DirectBilling.isDirect) { DirectBilling.purchase(activity, DIRECT_MONTHLY); return }
        val details = productDetails
        val token = details?.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (details == null || token == null) return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(token)
                        .build(),
                ),
            )
            .build()
        billingClient?.launchBillingFlow(activity, params)
    }

    private val purchasesResponseListener = PurchasesResponseListener { _, purchases ->
        val purchase = purchases.firstOrNull()
        val subscribed = purchase?.purchaseState == Purchase.PurchaseState.PURCHASED
        _state.value = _state.value.copy(isSubscribed = subscribed, isLoading = false)
        if (purchase?.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            acknowledge(purchase)
        }
    }

    private fun loadProduct() {
        val client = billingClient ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { _, queryResult ->
            val details = queryResult.productDetailsList.firstOrNull()
            productDetails = details
            val price = details
                ?.subscriptionOfferDetails
                ?.firstOrNull()
                ?.pricingPhases
                ?.pricingPhaseList
                ?.firstOrNull()
                ?.formattedPrice
            _state.value = _state.value.copy(productPrice = price, isLoading = false)
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        if (purchase.isAcknowledged) {
                            refreshSubscriptions()
                        } else {
                            acknowledge(purchase)
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Telemetry.event("premium_purchase_cancelled")
            }
            else -> {
                Telemetry.event("premium_purchase_failed", mapOf("code" to result.responseCode))
                refreshSubscriptions()
            }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        billingClient?.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
        ) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                refreshSubscriptions()
            } else {
                Telemetry.event("premium_ack_failed", mapOf("code" to result.responseCode))
            }
        }
    }
}