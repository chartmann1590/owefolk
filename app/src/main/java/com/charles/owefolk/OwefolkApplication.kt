package com.charles.owefolk

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.charles.owefolk.observability.Telemetry
import com.charles.owefolk.translate.TranslationManager
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
class OwefolkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.initialize(this)
        TranslationManager.initialize(this)
        FirebaseApp.initializeApp(this)
        FirebaseEnvironment.configure()
        FirebaseCrashlytics.getInstance().setCustomKey("build_mode", BuildConfig.BUILD_TYPE)
        FirebaseRemoteConfig.getInstance().apply {
            setDefaultsAsync(mapOf("payment_handoffs_enabled" to true, "digest_enabled" to true))
            fetchAndActivate()
        }
    }
}
