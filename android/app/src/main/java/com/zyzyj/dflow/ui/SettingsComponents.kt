package com.zyzyj.dflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Shared pieces for the two configuration screens.
 *
 * Both screens were a single flat scroll of labelled rows separated by dividers:
 * twenty-odd fields in a line, five unrelated topics interleaved, and no way to
 * see the state of anything without reading every field. The components here give
 * them the same shape — collapsible cards that state their own status in the
 * header — so a user can find a setting and tell whether it is configured
 * without opening anything.
 */

/**
 * A collapsible section.
 *
 * The header always shows a one-line [summary] of the current state, which is
 * what makes collapsing safe: nothing important is hidden behind a tap, only the
 * controls are.
 */
@Composable
fun SettingsCard(
    icon: ImageVector,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    /** Draws attention to the header (used when this section needs action). */
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = if (highlighted) 3.dp else 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(
                            color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (summary.isNotBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

/**
 * A labelled text field with consistent spacing.
 *
 * Secrets are shown by default with an eye toggle. Masking them by default was
 * actively harmful: the platform secure keyboard suppresses clipboard suggestions,
 * so pasting a key — the normal way to enter one — was fought by the UI, and a
 * mistyped key could not be seen to be wrong.
 */
@Composable
fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String = "",
    secret: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    var masked by remember(secret) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotBlank()) ({ Text(placeholder) }) else null,
        supportingText = if (hint.isNotBlank()) ({ Text(hint) }) else null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        visualTransformation = if (secret && masked) PasswordVisualTransformation()
        else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Text else keyboardType,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        trailingIcon = if (secret && value.isNotEmpty()) {
            {
                IconButton(onClick = { masked = !masked }) {
                    Icon(
                        imageVector = if (masked) Icons.Filled.VisibilityOff
                        else Icons.Filled.Visibility,
                        contentDescription = if (masked) "显示" else "隐藏",
                    )
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Explanatory text.
 *
 * Tonal rather than plain grey text, so it reads as advice belonging to the
 * section instead of looking like another setting that failed to render.
 */
@Composable
fun SettingsNote(text: String, tone: NoteTone = NoteTone.Neutral) {
    val container = when (tone) {
        NoteTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
        NoteTone.Info -> MaterialTheme.colorScheme.secondaryContainer
        NoteTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        NoteTone.Danger -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when (tone) {
        NoteTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        NoteTone.Info -> MaterialTheme.colorScheme.onSecondaryContainer
        NoteTone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        NoteTone.Danger -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

enum class NoteTone { Neutral, Info, Warning, Danger }

/** Small coloured chip for a state that is worth reading at a glance. */
@Composable
fun StatusChip(
    text: String,
    tone: NoteTone = NoteTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val (container, content) = when (tone) {
        NoteTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest to
            MaterialTheme.colorScheme.onSurfaceVariant
        NoteTone.Info -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        NoteTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        NoteTone.Danger -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** A switch row: label, explanation, control. */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    warning: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** One line of the result of a probe, with a pass/fail marker. */
@Composable
fun ProbeRow(ok: Boolean, name: String, detail: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A monospaced, selectable value with a copy button.
 *
 * Used for tokens, URLs and config snippets — the things this screen exists to
 * move into another app, where a mistyped character costs a confusing failure.
 */
@Composable
fun CopyableValue(
    value: String,
    onCopied: () -> Unit,
    label: String = "",
    maskable: Boolean = false,
) {
    var revealed by remember { mutableStateOf(!maskable) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            if (label.isNotBlank()) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (revealed) value else "•".repeat(value.length.coerceAtMost(40)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (maskable) {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            imageVector = if (revealed) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = if (revealed) "隐藏" else "显示",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                CopyButton(value = value, onCopied = onCopied)
            }
        }
    }
}

/**
 * A sticky bar that appears only while there are unsaved edits.
 *
 * Two problems this solves at once: the save button used to sit at the very bottom
 * of a twenty-field scroll, and nothing anywhere indicated that edits were
 * pending — so a proxy change could be scrolled away from and silently lost.
 */
@Composable
fun UnsavedBar(
    visible: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "有未保存的修改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onDiscard, enabled = !saving) {
                    Text("撤销")
                }
                androidx.compose.material3.Button(onClick = onSave, enabled = !saving) {
                    if (saving) {
                        androidx.compose.material3.CircularProgressIndicator(
                            Modifier.size(16.dp), strokeWidth = 2.dp,
                        )
                    } else {
                        Text("保存")
                    }
                }
            }
        }
    }
}

/** Screen title with a subtitle, so each screen says what it is for. */
@Composable
fun ScreenHeader(title: String, subtitle: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Copy-to-clipboard button with a brief confirmation.
 *
 * The confirmation matters: copying is silent otherwise, and a user who is not
 * sure it worked will select the text by hand — which is exactly the fiddly path
 * this button exists to avoid.
 */
@Composable
fun CopyButton(value: String, onCopied: () -> Unit, label: String = "复制") {
    val clipboard = LocalClipboardManager.current
    var justCopied by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(justCopied) {
        if (justCopied) {
            kotlinx.coroutines.delay(1600)
            justCopied = false
        }
    }
    TextButton(onClick = {
        clipboard.setText(AnnotatedString(value))
        justCopied = true
        onCopied()
    }) {
        Icon(
            imageVector = if (justCopied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(if (justCopied) "已复制" else label, Modifier.padding(start = 6.dp))
    }
}
