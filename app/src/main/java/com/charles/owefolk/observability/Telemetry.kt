package com.charles.owefolk.observability

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

object Telemetry {
    private var analytics: FirebaseAnalytics? = null
    private val forbidden = setOf("amount", "name", "email", "note", "handle", "token", "group_id", "user_id")

    fun initialize(context: Context) {
        analytics = FirebaseAnalytics.getInstance(context)
    }

    fun event(name: String, parameters: Map<String, Any> = emptyMap()) {
        check(parameters.keys.none { key -> forbidden.any { blocked -> key.contains(blocked, ignoreCase = true) } }) {
            "Sensitive telemetry key"
        }
        val bundle = Bundle().apply {
            parameters.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value.take(60))
                    is Int -> putLong(key, value.toLong())
                    is Long -> putLong(key, value)
                    is Boolean -> putString(key, value.toString())
                }
            }
        }
        analytics?.logEvent(name, bundle)
    }

    fun record(error: Throwable, operation: String) {
        FirebaseCrashlytics.getInstance().apply {
            setCustomKey("operation", operation)
            recordException(error)
        }
    }
}
