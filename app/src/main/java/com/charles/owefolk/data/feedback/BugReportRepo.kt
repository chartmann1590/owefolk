package com.charles.owefolk.data.feedback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/** DataStore holding the locally-recorded list of submitted feedback reports. */
private val Context.feedbackDataStore by preferencesDataStore(name = "feedback_bug_reports")

/**
 * Persists a small JSON-serialized [List] of [BugReport] under the key
 * `bug_reports_list`. New reports upsert by issue number so re-submitting or
 * re-syncing a status never creates duplicates. The newest report sorts first.
 *
 * Corrupt stored JSON is tolerated instead of crashing the app; the flow
 * simply returns an empty list in that case.
 */
class BugReportRepo(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val key = stringPreferencesKey("bug_reports_list")

    val bugReports: Flow<List<BugReport>> = context.feedbackDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs[key]?.let(::decode) ?: emptyList() }

    suspend fun getBugReportsList(): List<BugReport> = bugReports.first()

    suspend fun saveBugReport(report: BugReport) {
        val current = getBugReportsList()
        updateBugReports(current.upsert(report).sortedNewestFirst())
    }

    suspend fun updateBugReports(reports: List<BugReport>) {
        context.feedbackDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(BugReport.serializer()), reports)
        }
    }

    private fun decode(stored: String): List<BugReport> = runCatching {
        json.decodeFromString(ListSerializer(BugReport.serializer()), stored)
    }.getOrDefault(emptyList())

    private fun List<BugReport>.upsert(report: BugReport): List<BugReport> =
        if (any { it.number == report.number }) map { if (it.number == report.number) report else it }
        else this + report
}

private fun List<BugReport>.sortedNewestFirst(): List<BugReport> =
    sortedByDescending { it.number }
