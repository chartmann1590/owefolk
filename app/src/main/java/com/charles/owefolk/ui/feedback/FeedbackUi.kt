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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.charles.owefolk.R
import com.charles.owefolk.data.feedback.BugReport
import com.charles.owefolk.data.feedback.BugReportRepo
import com.charles.owefolk.data.feedback.DiagnosticsHelper
import com.charles.owefolk.data.feedback.GithubApi
import com.charles.owefolk.data.feedback.GithubComment
import com.charles.owefolk.data.feedback.ImageHelper
import com.charles.owefolk.translate.TranslatedTextResource
import com.charles.owefolk.translate.rememberTranslated
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
            TranslatedTextResource(R.string.feedback_title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { showReportDialog = true }) {
                Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(6.dp)); TranslatedTextResource(R.string.feedback_report_button)
            }
        }
        if (reports.isEmpty()) {
            TranslatedTextResource(R.string.feedback_empty, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            reports.forEach { report -> ReportRow(report) { openReport = report } }
        }
        submittedMessage?.let {
            Text(rememberTranslated(it), color = Mint, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        TranslatedTextResource(
            R.string.feedback_privacy_note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        )
    }

    if (showReportDialog) {
        ReportProblemDialog(
            repo = reportRepo,
            onDismiss = { showReportDialog = false },
            onSubmitted = { submittedMessage = context.getString(R.string.feedback_submitted) },
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
                Text(rememberTranslated(report.title.removePrefix("[Feedback] ")), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rememberTranslated("#${report.number} • ${prettyDate(report.createdAt)}"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            rememberTranslated(if (open) stringResource(R.string.feedback_status_open) else stringResource(R.string.feedback_status_closed)),
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
        title = { TranslatedTextResource(R.string.feedback_dialog_title) },
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
                        rememberTranslated(stringResource(R.string.feedback_dialog_warning)),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), label = { TranslatedTextResource(R.string.feedback_subject) }, singleLine = true)
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { TranslatedTextResource(R.string.feedback_description) }, minLines = 4, maxLines = 8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeDiagnostics, onCheckedChange = { includeDiagnostics = it })
                    Column(Modifier.weight(1f)) {
                        TranslatedTextResource(R.string.feedback_include_diagnostics)
                        TranslatedTextResource(R.string.feedback_diagnostics_desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { TranslatedTextResource(R.string.feedback_name_optional) }, singleLine = true)
                OutlinedTextField(email, { email = it.take(120) }, Modifier.fillMaxWidth(), label = { TranslatedTextResource(R.string.feedback_email_optional) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !submitting && configError == null,
                    ) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); TranslatedTextResource(R.string.feedback_attach_image) }
                    if (attachedUri != null) {
                        TranslatedTextResource(R.string.feedback_image_selected, style = MaterialTheme.typography.bodySmall, color = Mint)
                        TextButton(onClick = { attachedUri = null }, enabled = !submitting) { TranslatedTextResource(R.string.feedback_remove) }
                    }
                }
                attachedUri?.let { AttachmentPreview(it) }
                if (configError != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .6f), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            rememberTranslated(stringResource(R.string.feedback_not_configured, configError)),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                error?.let {
                    Text(rememberTranslated(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                                append("## Description\n\n").append(capturedDesc.ifBlank { context.getString(R.string.feedback_no_description) })
                                append("\n\n## Contact Info\n\n")
                                append("- Name: ").append(capturedName.ifBlank { context.getString(R.string.feedback_not_provided) }).append("\n")
                                append("- Email: ").append(capturedEmail.ifBlank { context.getString(R.string.feedback_not_provided) }).append("\n")
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
                            error = context.getString(R.string.feedback_submit_failed, t.message ?: context.getString(R.string.feedback_not_provided))
                        } finally {
                            submitting = false
                        }
                    }
                },
            ) {
                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, null)
                Spacer(Modifier.width(8.dp)); TranslatedTextResource(R.string.feedback_submit)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { TranslatedTextResource(R.string.feedback_cancel) } },
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
            loadError = context.getString(R.string.feedback_issue_state_failed, t.message ?: context.getString(R.string.feedback_not_provided))
        }
        try {
            comments = GithubApi.instance.listComments(report.number)
        } catch (t: Throwable) {
            comments = emptyList()
            val commentsError = context.getString(R.string.feedback_comments_failed, t.message ?: context.getString(R.string.feedback_not_provided))
            loadError = listOfNotNull(loadError, commentsError).joinToString("\n")
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(rememberTranslated(stringResource(R.string.feedback_issue_title, report.number)), style = MaterialTheme.typography.titleMedium)
                Text(rememberTranslated(report.title.removePrefix("[Feedback] ")), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        }) { TranslatedTextResource(R.string.feedback_open_github) }
                    }
                }
                if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                loadError?.let { Text(rememberTranslated(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (comments.isNotEmpty()) {
                    TranslatedTextResource(R.string.feedback_comments, style = MaterialTheme.typography.titleSmall)
                    comments.forEach { CommentRow(it) }
                } else if (!loading) {
                    TranslatedTextResource(R.string.feedback_no_comments, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                TranslatedTextResource(R.string.feedback_reply, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(reply, { reply = it }, Modifier.fillMaxWidth(), label = { TranslatedTextResource(R.string.feedback_reply_label) }, minLines = 2, maxLines = 6)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !posting,
                    ) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); TranslatedTextResource(R.string.feedback_attach) }
                    if (replyUri != null) {
                        TranslatedTextResource(R.string.feedback_image_selected, style = MaterialTheme.typography.bodySmall, color = Mint)
                        TextButton(onClick = { replyUri = null }, enabled = !posting) { TranslatedTextResource(R.string.feedback_remove) }
                    }
                }
                replyUri?.let { AttachmentPreview(it) }
                postError?.let { Text(rememberTranslated(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
                            postError = context.getString(R.string.feedback_reply_failed, t.message ?: context.getString(R.string.feedback_not_provided))
                        } finally {
                            posting = false
                        }
                    }
                },
            ) {
                if (posting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Comment, null)
                Spacer(Modifier.width(8.dp)); TranslatedTextResource(R.string.feedback_post_reply)
            }
        },
        dismissButton = { TextButton(onDismiss) { TranslatedTextResource(R.string.feedback_close) } },
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
            Text(rememberTranslated(renderCommentBody(comment.body)), style = MaterialTheme.typography.bodyMedium)
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
            contentDescription = stringResource(R.string.feedback_attachment_preview),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp)),
        )
    }
    if (failed) {
        TranslatedTextResource(
            R.string.feedback_preview_unavailable,
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
