package com.charles.owefolk.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.charles.owefolk.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@Composable
fun AdMobBanner(modifier: Modifier = Modifier) {
    val ready by AdsManager.adsReady.collectAsState()
    if (!ready || BuildConfig.ADMOB_BANNER_ID.isBlank()) return
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp.coerceAtLeast(320)
    val adView = remember(context, widthDp) {
        AdView(context).apply {
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
        }
    }
    LaunchedEffect(adView) { adView.loadAd(AdRequest.Builder().build()) }
    DisposableEffect(adView) { onDispose { adView.destroy() } }
    AndroidView(factory = { adView }, modifier = modifier.fillMaxWidth())
}
