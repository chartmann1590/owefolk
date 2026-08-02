package com.charles.owefolk.ads

import android.app.Activity
import android.content.Context
import com.charles.owefolk.BuildConfig
import com.charles.owefolk.observability.Telemetry
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

object AdsManager {
    private const val PREFERENCES = "ads"
    private const val COMPLETED_EXPENSES = "completed_expenses"
    private const val LAST_INTERSTITIAL = "last_interstitial"
    private const val MIN_INTERSTITIAL_INTERVAL_MS = 15 * 60 * 1000L

    private val initialized = AtomicBoolean(false)
    private val _adsReady = MutableStateFlow(false)
    val adsReady = _adsReady.asStateFlow()
    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired = _privacyOptionsRequired.asStateFlow()
    private var interstitial: InterstitialAd? = null
    private var loadingInterstitial = false

    fun initialize(activity: Activity) {
        if (BuildConfig.ADMOB_TEST_DEVICE_ID.isNotBlank()) {
            MobileAds.setRequestConfiguration(
                RequestConfiguration.Builder().setTestDeviceIds(listOf(BuildConfig.ADMOB_TEST_DEVICE_ID)).build(),
            )
        }
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val parameters = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                _privacyOptionsRequired.value = consentInformation.privacyOptionsRequirementStatus ==
                    ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    error?.let { Telemetry.record(IllegalStateException(it.message), "ad_consent_form") }
                    if (consentInformation.canRequestAds()) startAds(activity)
                }
                if (consentInformation.canRequestAds()) startAds(activity)
            },
            { error ->
                Telemetry.record(IllegalStateException(error.message), "ad_consent_update")
                if (consentInformation.canRequestAds()) startAds(activity)
            },
        )
    }

    private fun startAds(activity: Activity) {
        if (!initialized.compareAndSet(false, true)) return
        MobileAds.initialize(activity) {
            _adsReady.value = true
            activity.runOnUiThread { loadInterstitial(activity) }
        }
    }

    private fun loadInterstitial(activity: Activity) {
        if (loadingInterstitial || interstitial != null || BuildConfig.ADMOB_INTERSTITIAL_ID.isBlank()) return
        loadingInterstitial = true
        InterstitialAd.load(
            activity,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadingInterstitial = false
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingInterstitial = false
                    Telemetry.event("interstitial_load_failed", mapOf("error_code" to error.code))
                }
            },
        )
    }

    fun onExpenseSaved(activity: Activity) {
        val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val completed = preferences.getInt(COMPLETED_EXPENSES, 0) + 1
        preferences.edit().putInt(COMPLETED_EXPENSES, completed).apply()
        val lastShown = preferences.getLong(LAST_INTERSTITIAL, 0L)
        val frequencyReached = completed == 1 || completed % 3 == 0
        if (!frequencyReached || System.currentTimeMillis() - lastShown < MIN_INTERSTITIAL_INTERVAL_MS) {
            loadInterstitial(activity)
            return
        }
        val ad = interstitial ?: run { loadInterstitial(activity); return }
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = loadInterstitial(activity)
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                Telemetry.event("interstitial_show_failed", mapOf("error_code" to error.code))
                loadInterstitial(activity)
            }
        }
        preferences.edit().putLong(LAST_INTERSTITIAL, System.currentTimeMillis()).apply()
        ad.show(activity)
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            error?.let { Telemetry.record(IllegalStateException(it.message), "ad_privacy_options") }
        }
    }
}
