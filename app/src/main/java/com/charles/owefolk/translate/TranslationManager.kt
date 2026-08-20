package com.charles.owefolk.translate

import android.content.Context
import com.charles.owefolk.observability.Telemetry
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.LinkedHashMap

enum class ModelStatus { NONE, DOWNLOADING, READY, ERROR }

data class TranslationUiState(
    val target: String? = null,
    val status: ModelStatus = ModelStatus.NONE,
    val error: String? = null,
) {
    val active: Boolean get() = status == ModelStatus.READY && !target.isNullOrBlank()
}

object TranslationManager {
    private const val PREFS = "translation"
    private const val KEY_TARGET = "target_language"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TranslationUiState())
    val state: StateFlow<TranslationUiState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var initialized = false

    private var translator: Translator? = null
    private var currentLang: String? = null
    private val translateMutex = Mutex()

    private val cache = object : LinkedHashMap<String, String>(384, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 3000
    }

    private var warmupStarted = false
    private var downloadTrace: Trace? = null

    /** Common in-app strings, pre-translated once the model is ready so screens stay snappy. */
    private val WARM_STRINGS = listOf(
        "Continue", "Cancel", "Save", "Confirm", "Back", "Finish", "New", "Groups", "Home",
        "Activity", "Profile", "Add expense", "Create", "Join", "Skip for now", "Sign in",
        "Create account", "Continue with Google", "Email address", "Password", "Shares",
        "All settled up", "You're owed", "You owe", "Your net balance", "Recent activity",
        "Not received", "I've sent it", "Invite friends", "Send a friendly reminder",
        "Members", "People", "Open", "Close", "Retry", "Remove", "Attach", "Submit",
        "Post reply", "Comments", "Your name", "Amount", "Currency", "Emoji", "Group name",
        "Camera", "Photos", "Split with", "What was it for?", "Display language", "Sign out",
        "Delete account", "Privacy policy", "Website", "Language", "Keep account",
        "Delete permanently", "Done",
    )

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        val ctx = appContext!!
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TARGET, null)
        if (!saved.isNullOrBlank() && AppLanguage.byTag(saved) != null) {
            _state.value = _state.value.copy(target = saved, status = ModelStatus.DOWNLOADING)
            scope.launch { activateLanguage(saved) }
        }
    }

    suspend fun setLanguage(tag: String?) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TARGET, tag).apply()
        if (tag.isNullOrBlank()) {
            Telemetry.event("translation_disabled")
            reset(ctx)
        } else {
            if (AppLanguage.byTag(tag) != null) activateLanguage(tag)
        }
    }

    suspend fun translate(text: String): String? {
        if (text.isBlank()) return text
        if (_state.value.status != ModelStatus.READY) return null
        if (shouldSkip(text)) return text
        cache[text]?.let { return it }
        val tr = translator ?: return null
        return translateMutex.withLock {
            cache[text]?.let { return@withLock it }
            runCatching {
                val result = tr.translate(text).await()
                if (result.isNotBlank()) cache[text] = result
                result
            }.onFailure { t -> Telemetry.record(t, "translation") }.getOrNull()
        }
    }

    /**
     * Runs a single translation off the UI thread and hands the result back on [callback].
     * Used by code outside Compose (e.g. notifications) where [translate] can't be awaited.
     * Returns immediately with the original text if translation isn't active or should be skipped.
     */
    fun translateAsync(text: String, callback: (String) -> Unit) {
        if (!_state.value.active || text.isBlank() || shouldSkip(text)) {
            callback(text)
            return
        }
        scope.launch { callback(translate(text) ?: text) }
    }

    fun scheduleWarm() {
        if (warmupStarted || _state.value.status != ModelStatus.READY) return
        warmupStarted = true
        scope.launch {
            val trace = FirebasePerformance.getInstance().newTrace("translation_warmup_batch")
            trace.start()
            runCatching {
                WARM_STRINGS.forEach { s -> runCatching { translate(s) } }
                Telemetry.event("translation_warmup_batch")
            }
            trace.stop()
        }
    }

    private suspend fun activateLanguage(tag: String) {
        val ctx = appContext ?: return
        val language = requireNotNull(AppLanguage.byTag(tag)).translate
        _state.value = _state.value.copy(target = tag, status = ModelStatus.DOWNLOADING, error = null)
        val previous = currentLang
        downloadTrace = FirebasePerformance.getInstance().newTrace("translation_model_download").also(Trace::start)
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(language)
                .build()
            val newTranslator = Translation.getClient(options)
            newTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val old = translator
            translator = newTranslator
            currentLang = language
            old?.close()
            if (previous != null && previous != language) {
                scope.launch {
                    runCatching {
                        RemoteModelManager.getInstance()
                            .deleteDownloadedModel(TranslateRemoteModel.Builder(previous).build())
                    }
                }
            }
            cache.clear()
            _state.value = _state.value.copy(status = ModelStatus.READY, error = null)
            Telemetry.event(
                if (previous == null) "translation_enabled" else "translation_language_changed",
                mapOf("language" to tag),
            )
            scheduleWarm()
        } catch (t: Throwable) {
            Telemetry.record(t, "translation_model_download")
            _state.value = _state.value.copy(status = ModelStatus.ERROR, error = t.message ?: "Download failed")
        } finally {
            downloadTrace?.stop()
            downloadTrace = null
        }
    }

    private fun reset(ctx: Context) {
        cache.clear()
        warmupStarted = false
        _state.value = TranslationUiState()
        val old = translator
        translator = null
        scope.launch {
            old?.close()
            currentLang?.let { lang ->
                runCatching {
                    RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(lang).build())
                }
            }
            currentLang = null
        }
    }

    internal fun shouldSkip(text: String): Boolean {
        if (text.length < 3) return true
        val hasLetter = text.any(Char::isLetter)
        if (!hasLetter) return true
        if (!text.any { it in 'a'..'z' || it in 'A'..'Z' }) return true
        if (text.contains("http://") || text.contains("https://")) return true
        if (text.contains('@') && !text.any(Char::isWhitespace)) return true
        return false
    }
}