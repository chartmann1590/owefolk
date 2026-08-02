package com.charles.owefolk.ui.feedback

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.charles.owefolk.data.feedback.BugReport
import com.charles.owefolk.data.feedback.BugReportRepo
import com.charles.owefolk.data.feedback.DiagnosticsHelper
import com.charles.owefolk.data.feedback.GithubApi
import com.charles.owefolk.data.feedback.GithubComment
import com.charles.owefolk.data.feedback.ImageHelper
import com.charles.owefolk.ui.theme.Coral
import com.charles.owefolk.ui.theme.Mint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportFeedbackSection(reportRepo: BugReportRepo) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reports by reportRepo.bugReports.collectAsState(initial = emptyList())
    var showReportDialog by remember { mutableStateOf(false) }
    var openReport by remember { mutableStateOf<BugReport?>(null) }
    var submittedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(submittedMessage) {
        if (submittedMessage != null) {
            delay(5_000)
            submittedMessage = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Support & Feedback", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { showReportDialog = true }) {
                Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(6.dp)); Text("Report a problem")
            }
        }
        if (reports.isEmpty()) {
            Text("Nothing yet. Report a bug or share feedback and it gets posted to this app's GitHub issue tracker.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            reports.forEach { report -> ReportRow(report) { openReport = report } }
        }
        submittedMessage?.let {
            Text(it, color = Mint, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Your report is posted to the project's public GitHub issue tracker. Do not include passwords, keys, medical or financial info, or anything you don't want visible to maintainers. Screenshots may contain private information.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        )
    }

    if (showReportDialog) {
        ReportProblemDialog(
            repo = reportRepo,
            onDismiss = { showReportDialog = false },
            onSubmitted = { submittedMessage = "Thanks — your report was posted and saved on this device." },
        )
    }
    openReport?.let { report ->
        IssueDetailsDialog(
            report = report,
            repo = reportRepo,
            onDismiss = { openReport = null },
        )
    }
}

@Composable
private fun ReportRow(report: BugReport, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Mint.copy(alpha = .14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.BugReport, null, tint = Mint) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(report.title.removePrefix("[Feedback] "), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("#${report.number} • ${prettyDate(report.createdAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge(report.status)
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val open = status.equals("open", ignoreCase = true)
    val color = if (open) Mint else Coral
    Surface(
        color = color.copy(alpha = .16f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            if (open) "Open" else "Closed",
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportProblemDialog(
    repo: BugReportRepo,
    onDismiss: () -> Unit,
    onSubmitted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var attachedUri by remember { mutableStateOf<Uri?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        attachedUri = uri
    }

    val api = GithubApi.instance
    val configError = api.configurationError

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Report a problem") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        "Your report will be submitted to this app's GitHub issue tracker. Do not include passwords, private keys, medical, financial, or anything you don't want visible to repository maintainers. If this repo is public, your report may be publicly visible. Screenshots may contain private information.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Subject *") }, singleLine = true)
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description *") }, minLines = 4, maxLines = 8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeDiagnostics, onCheckedChange = { includeDiagnostics = it })
                    Column(Modifier.weight(1f)) {
                        Text("Include diagnostics")
                        Text("App version, device, Android version, locale, storage/memory", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(email, { email = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Email (optional)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !submitting && configError == null,
                    ) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Attach image") }
                    if (attachedUri != null) {
                        Text("Image selected", style = MaterialTheme.typography.bodySmall, color = Mint)
                        TextButton(onClick = { attachedUri = null }, enabled = !submitting) { Text("Remove") }
                    }
                }
                attachedUri?.let { AttachmentPreview(it) }
                if (configError != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .6f), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            "Feedback isn't configured in this build. $configError",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && description.isNotBlank() && !submitting && configError == null,
                onClick = {
                    submitting = true
                    error = null
                    val capturedTitle = title
                    val capturedDesc = description
                    val capturedName = name
                    val capturedEmail = email
                    val capturedDiagnostics = if (includeDiagnostics) DiagnosticsHelper.collect(context) else null
                    val capturedUri = attachedUri
                    scope.launch {
                        try {
                            val attachmentMd = if (capturedUri != null) {
                                val image = ImageHelper.uriToEncodedImage(context, capturedUri)
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                val suffix = "%04x".format(Random.nextInt(0x10000))
                                val fileName = "issue-$stamp-$suffix.${image.extension}"
                                val downloadUrl = api.uploadAsset(fileName, image.base64, "Add feedback attachment $fileName")
                                "## Attachment\n\n![Screenshot]($downloadUrl)\n\n"
                            } else ""
                            val body = buildString {
                                append("## Description\n\n").append(capturedDesc.ifBlank { "No description provided." })
                                append("\n\n## Contact Info\n\n")
                                append("- Name: ").append(capturedName.ifBlank { "Not provided" }).append("\n")
                                append("- Email: ").append(capturedEmail.ifBlank { "Not provided" }).append("\n")
                                if (attachmentMd.isNotBlank()) append("\n").append(attachmentMd)
                                if (capturedDiagnostics != null) append("\n").append(capturedDiagnostics)
                            }
                            val issue = api.createIssue("[Feedback] $capturedTitle", body)
                            repo.saveBugReport(
                                BugReport(
                                    number = issue.number,
                                    title = issue.title,
                                    status = issue.state,
                                    createdAt = issue.createdAt,
                                    htmlUrl = issue.htmlUrl,
                                ),
                            )
                            onSubmitted()
                            onDismiss()
                        } catch (t: Throwable) {
                            error = "Could not submit: ${t.message ?: "Unknown error"}"
                        } finally {
                            submitting = false
                        }
                    }
                },
            ) {
                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, null)
                Spacer(Modifier.width(8.dp)); Text("Submit")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueDetailsDialog(
    report: BugReport,
    repo: BugReportRepo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var issue by remember { mutableStateOf<GithubIssueLite?>(null) }
    var comments by remember { mutableStateOf<List<GithubComment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    var reply by remember { mutableStateOf("") }
    var replyUri by remember { mutableStateOf<Uri?>(null) }
    var posting by remember { mutableStateOf(false) }
    var postError by remember { mutableStateOf<String?>(null) }
    val apiConfigError = GithubApi.instance.configurationError

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        replyUri = uri
    }

    LaunchedEffect(report.number, refreshKey) {
        loading = true
        loadError = null
        if (apiConfigError != null) {
            loadError = apiConfigError
            loading = false
            return@LaunchedEffect
        }
        try {
            val liveIssue = GithubApi.instance.getIssue(report.number)
            issue = GithubIssueLite(liveIssue.state, liveIssue.htmlUrl)
            repo.saveBugReport(
                report.copy(status = liveIssue.state, htmlUrl = liveIssue.htmlUrl, title = liveIssue.title),
            )
        } catch (t: Throwable) {
            loadError = "Couldn't fetch the latest issue state: ${t.message ?: "Unknown error"}"
        }
        try {
            comments = GithubApi.instance.listComments(report.number)
        } catch (t: Throwable) {
            comments = emptyList()
            val commentsError = "Couldn't load comments: ${t.message ?: "Unknown error"}"
            loadError = listOfNotNull(loadError, commentsError).joinToString("\n")
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Issue #${report.number}", style = MaterialTheme.typography.titleMedium)
                Text(report.title.removePrefix("[Feedback] "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(issue?.status ?: report.status)
                    Spacer(Modifier.width(8.dp))
                    Text(prettyDate(report.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    (issue?.htmlUrl ?: report.htmlUrl).takeIf(String::isNotBlank)?.let { url ->
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }) { Text("Open on GitHub") }
                    }
                }
                if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (comments.isNotEmpty()) {
                    Text("Comments", style = MaterialTheme.typography.titleSmall)
                    comments.forEach { CommentRow(it) }
                } else if (!loading) {
                    Text("No comments yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Reply", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(reply, { reply = it }, Modifier.fillMaxWidth(), label = { Text("Your reply") }, minLines = 2, maxLines = 6)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !posting,
                    ) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Attach") }
                    if (replyUri != null) {
                        Text("Image selected", style = MaterialTheme.typography.bodySmall, color = Mint)
                        TextButton(onClick = { replyUri = null }, enabled = !posting) { Text("Remove") }
                    }
                }
                replyUri?.let { AttachmentPreview(it) }
                postError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = reply.isNotBlank() && !posting && apiConfigError == null,
                onClick = {
                    posting = true
                    postError = null
                    val capturedReply = reply
                    val capturedUri = replyUri
                    scope.launch {
                        try {
                            val attachmentMd = if (capturedUri != null) {
                                val image = ImageHelper.uriToEncodedImage(context, capturedUri)
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                val suffix = "%04x".format(kotlin.random.Random.nextInt(0x10000))
                                val fileName = "comment-$stamp-$suffix.${image.extension}"
                                val url = GithubApi.instance.uploadAsset(fileName, image.base64, "Add feedback attachment $fileName")
                                "\n\n## Attachment\n\n![Screenshot]($url)\n"
                            } else ""
                            GithubApi.instance.postComment(report.number, "## Reply\n\n$capturedReply$attachmentMd")
                            reply = ""
                            replyUri = null
                            refreshKey++
                        } catch (t: Throwable) {
                            postError = "Reply failed: ${t.message ?: "Unknown error"}"
                        } finally {
                            posting = false
                        }
                    }
                },
            ) {
                if (posting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Comment, null)
                Spacer(Modifier.width(8.dp)); Text("Post reply")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun CommentRow(comment: GithubComment) {
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.user.login, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(prettyDate(comment.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(renderCommentBody(comment.body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AttachmentPreview(uri: Uri) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { runCatching { ImageHelper.previewBitmap(context, uri) }.getOrNull() }
        failed = bitmap == null
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Selected attachment preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp)),
        )
    }
    if (failed) {
        Text(
            "Preview unavailable. Remove this image and choose another.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private data class GithubIssueLite(val status: String, val htmlUrl: String)

private fun renderCommentBody(body: String): String =
    // Strip the markdown section headers we add so the comment reads cleanly in the UI.
    body
        .replace(Regex("(?m)^## .+$"), "")
        .replace(Regex("(?m)^!\\[.*?]\\((.*?)\\)$")) { "📷 Attachment" }
        .trim()

private fun prettyDate(iso: String): String {
    return runCatching {
        val cleaned = iso.replace("Z", "+00:00")
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(cleaned)
        parsed?.let { SimpleDateFormat("MMM d, yyyy", Locale.US).format(it) } ?: iso
    }.getOrDefault(iso)
}
