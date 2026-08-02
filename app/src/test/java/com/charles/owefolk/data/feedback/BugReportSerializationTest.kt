package com.charles.owefolk.data.feedback

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun localReportListRoundTrips() {
        val reports = listOf(
            BugReport(
                number = 42,
                title = "A useful report",
                status = "open",
                createdAt = "2026-08-02T12:00:00Z",
                htmlUrl = "https://github.com/example/app/issues/42",
            ),
        )

        assertEquals(reports, json.decodeFromString<List<BugReport>>(json.encodeToString(reports)))
    }

    @Test
    fun githubIssueUsesApiFieldNames() {
        val issue = json.decodeFromString<GithubIssue>(
            """{"number":7,"title":"Feedback","state":"closed","html_url":"https://example.test/7","created_at":"2026-08-02T12:00:00Z","future_field":true}""",
        )

        assertEquals("https://example.test/7", issue.htmlUrl)
        assertEquals("2026-08-02T12:00:00Z", issue.createdAt)
        assertTrue(json.encodeToString(issue).contains("\"html_url\""))
    }
}
