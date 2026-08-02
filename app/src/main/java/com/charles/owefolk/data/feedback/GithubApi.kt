package com.charles.owefolk.data.feedback

import com.charles.owefolk.BuildConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Calls the project's narrowly scoped Cloudflare feedback API. The GitHub PAT
 * never enters this process: the Worker validates Firebase Auth and App Check
 * tokens, then adds its encrypted GitHub secret to the upstream request.
 */
class GithubApi(
    private val proxyBaseUrl: String,
    private val repoOwner: String,
    private val repoName: String,
    private val userAgent: String,
    private val tokenProvider: suspend () -> FeedbackTokens = ::firebaseTokens,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val logging = HttpLoggingInterceptor().apply {
        redactHeader("Authorization")
        redactHeader("X-Firebase-AppCheck")
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(logging)
        .build()

    val isConfigured: Boolean
        get() = proxyBaseUrl.startsWith("https://") && repoOwner.isNotBlank() && repoName.isNotBlank()

    val configurationError: String?
        get() = when {
            proxyBaseUrl.isBlank() -> "The feedback service URL is missing from this build."
            !proxyBaseUrl.startsWith("https://") -> "The feedback service must use HTTPS."
            repoOwner.isBlank() || repoName.isBlank() -> "The GitHub repository is missing from this build."
            else -> null
        }

    private fun fullUrl(path: String): String = "${proxyBaseUrl.trimEnd('/')}/v1/feedback/$path"

    private suspend fun jsonRequest(method: String, path: String, body: String? = null): Request {
        val tokens = tokenProvider()
        val requestBody = body?.toRequestBody(jsonMedia)
        return Request.Builder()
            .url(fullUrl(path))
            .method(method, if (method == "GET") null else requestBody ?: "{}".toRequestBody(jsonMedia))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${tokens.firebaseIdToken}")
            .header("X-Firebase-AppCheck", tokens.appCheckToken)
            .header("User-Agent", userAgent)
            .build()
    }

    private suspend fun exec(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response -> response.code to response.body?.string().orEmpty() }
    }

    suspend fun createIssue(title: String, body: String): GithubIssue {
        val (code, text) = exec(jsonRequest("POST", "issues", json.encodeToString(CreateIssueRequest(title, body))))
        if (code !in 200..299) throwGithubError(code, text)
        return json.decodeFromString(GithubIssue.serializer(), text)
    }

    suspend fun getIssue(number: Int): GithubIssue {
        val (code, text) = exec(jsonRequest("GET", "issues/$number"))
        if (code !in 200..299) throwGithubError(code, text)
        return json.decodeFromString(GithubIssue.serializer(), text)
    }

    suspend fun listComments(number: Int): List<GithubComment> {
        val (code, text) = exec(jsonRequest("GET", "issues/$number/comments"))
        if (code !in 200..299) throwGithubError(code, text)
        return json.decodeFromString(ListSerializer(GithubComment.serializer()), text)
    }

    suspend fun postComment(number: Int, body: String): GithubComment {
        val payload = json.encodeToString(PostCommentRequest(body))
        val (code, text) = exec(jsonRequest("POST", "issues/$number/comments", payload))
        if (code !in 200..299) throwGithubError(code, text)
        return json.decodeFromString(GithubComment.serializer(), text)
    }

    suspend fun uploadAsset(fileName: String, base64Content: String, commitMessage: String): String {
        val payload = json.encodeToString(UploadAssetRequest(commitMessage, base64Content))
        val (code, text) = exec(jsonRequest("PUT", "assets/$fileName", payload))
        if (code !in 200..299) throwGithubError(code, text)
        return json.decodeFromString(UploadAssetResponse.serializer(), text).content?.downloadUrl
            ?: throw FeedbackException("The attachment uploaded, but GitHub returned no download URL.")
    }

    private fun throwGithubError(code: Int, text: String): Nothing {
        val message = runCatching {
            val objectValue = json.parseToJsonElement(text) as? JsonObject
            objectValue?.get("error")?.jsonPrimitive?.contentOrNull
                ?: objectValue?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val friendly = when (code) {
            401 -> message ?: "Sign in again and make sure this build is verified."
            403 -> message ?: "The feedback service refused this request."
            404 -> message ?: "That report could not be found."
            413 -> "The selected attachment is too large."
            422 -> message ?: "GitHub could not validate this report."
            429 -> message ?: "Too many requests. Try again later."
            503 -> message ?: "Feedback is temporarily unavailable."
            else -> message ?: "Unexpected response from the feedback service."
        }
        throw FeedbackException(friendly)
    }

    companion object {
        val instance by lazy {
            GithubApi(
                proxyBaseUrl = BuildConfig.FEEDBACK_PROXY_URL,
                repoOwner = BuildConfig.GITHUB_REPO_OWNER,
                repoName = BuildConfig.GITHUB_REPO_NAME,
                userAgent = "${BuildConfig.FEEDBACK_APP_NAME}-Android/${BuildConfig.VERSION_NAME}",
            )
        }
    }
}

data class FeedbackTokens(val firebaseIdToken: String, val appCheckToken: String)

class FeedbackException(message: String) : Exception(message)

private suspend fun firebaseTokens(): FeedbackTokens {
    val user = FirebaseAuth.getInstance().currentUser
        ?: throw FeedbackException("Sign in before submitting feedback.")
    val authToken = user.getIdToken(false).await().token
        ?: throw FeedbackException("Your sign-in session could not be verified.")
    val appCheckToken = FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
    if (appCheckToken.isBlank()) throw FeedbackException("This app installation could not be verified.")
    return FeedbackTokens(authToken, appCheckToken)
}
