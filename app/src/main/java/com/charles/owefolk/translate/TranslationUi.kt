package com.charles.owefolk.translate

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalTextStyle
import com.charles.owefolk.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TranslationController(
    val state: StateFlow<TranslationUiState>,
    val translate: suspend (String) -> String?,
) {
    val active: Boolean get() = state.value.active
}

val LocalTranslation = staticCompositionLocalOf {
    TranslationController(MutableStateFlow(TranslationUiState()), { null })
}

@Composable
fun rememberTranslated(text: String): String {
    val controller = LocalTranslation.current
    val uiState by controller.state.collectAsState()
    if (!uiState.active) return text
    if (text.isEmpty()) return text
    var value by remember(text) { mutableStateOf(text) }
    LaunchedEffect(text, uiState.active) {
        value = controller.translate(text) ?: text
    }
    return value
}

@Composable
fun rememberTranslatedRes(resId: Int): String = rememberTranslated(stringResource(resId))

@Composable
fun rememberTranslatedRes(resId: Int, vararg formatArgs: Any): String =
    rememberTranslated(stringResource(resId, *formatArgs))

@Composable
fun TranslatedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
    translate: Boolean = true,
    interactive: Boolean = false,
) {
    val controller = LocalTranslation.current
    val translated = if (translate) rememberTranslated(text) else text
    val uiState by controller.state.collectAsState()
    val active = translate && uiState.active
    var showingOriginal by remember(text) { mutableStateOf(false) }
    LaunchedEffect(active) { showingOriginal = false }
    val display = if (active && showingOriginal) text else translated
    val textContent: @Composable () -> Unit = {
        Text(
            text = display,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            textAlign = textAlign,
            textDecoration = textDecoration,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout,
            style = style,
        )
    }
    if (active && interactive) {
        Box(modifier.clickable { showingOriginal = !showingOriginal }) {
            textContent()
            if (!showingOriginal) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Translate, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(10.dp))
                    Text(
                        rememberTranslatedRes(R.string.translation_auto_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    } else {
        textContent()
    }
}

@Composable
fun TranslatedTextResource(
    resId: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current,
    translate: Boolean = true,
) {
    TranslatedText(
        text = rememberTranslatedRes(resId),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        maxLines = maxLines,
        style = style,
        translate = translate,
    )
}

fun isTranslationDisclaimerDismissed(context: Context): Boolean =
    context.getSharedPreferences("translation", Context.MODE_PRIVATE).getBoolean("disclaimer_dismissed", false)

fun dismissTranslationDisclaimer(context: Context) {
    context.getSharedPreferences("translation", Context.MODE_PRIVATE)
        .edit().putBoolean("disclaimer_dismissed", true).apply()
}

@Composable
fun TranslationDisclaimerBar(show: Boolean = true, onDismiss: () -> Unit) {
    if (!show) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Translate, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
            Text(
                rememberTranslatedRes(R.string.translation_disclaimer),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, rememberTranslatedRes(R.string.translation_dismiss), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
fun TranslationPill() {
    val state by LocalTranslation.current.state.collectAsState()
    if (!state.active) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Translate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
            Text(
                rememberTranslatedRes(R.string.translation_auto_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun LanguagePickerUi(showDisclaimer: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val state by TranslationManager.state.collectAsState()
    val options = listOf(AppLanguage.OFF) + AppLanguage.ALL

    Column(
        Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (showDisclaimer) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(rememberTranslatedRes(R.string.translation_disclaimer), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
                    Text(rememberTranslatedRes(R.string.translation_device_only), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        options.forEach { lang ->
            val selected = state.target == lang.tag
            Row(
                Modifier.fillMaxWidth().clickable { scope.launch { TranslationManager.setLanguage(lang.tag.ifBlank { null }) } }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = { scope.launch { TranslationManager.setLanguage(lang.tag.ifBlank { null }) } })
                Spacer(Modifier.width(8.dp))
                Text(lang.nativeName, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                if (selected && lang.tag.isNotBlank() && state.status == ModelStatus.DOWNLOADING) {
                    LinearProgressIndicator(Modifier.size(20.dp))
                } else if (selected && lang.tag.isNotBlank() && state.status == ModelStatus.READY) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            if (selected && lang.tag.isNotBlank() && state.status == ModelStatus.DOWNLOADING) {
                Text(
                    rememberTranslatedRes(R.string.translation_downloading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, bottom = 8.dp),
                )
            }
            if (selected && lang.tag.isNotBlank() && state.status == ModelStatus.ERROR) {
                Row(Modifier.padding(start = 36.dp, bottom = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rememberTranslatedRes(R.string.translation_download_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scope.launch { TranslationManager.setLanguage(lang.tag) } }) {
                        Text(rememberTranslatedRes(R.string.translation_retry))
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            rememberTranslatedRes(R.string.language_device_only_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}