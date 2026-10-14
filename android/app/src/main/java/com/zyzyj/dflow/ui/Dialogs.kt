package com.zyzyj.dflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zyzyj.dflow.data.Favorite

/**
 * Prompt viewer and editor.
 *
 * The text is shown in a scrollable, selectable box rather than a single-line
 * field: prompts are long tag lists that the user mostly reads and copies, and
 * a field that scrolls horizontally would hide most of the content.
 */
@Composable
fun PromptDialog(
    favorite: Favorite,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(favorite.id) { mutableStateOf(favorite.prompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (readOnly) "提示词" else "编辑提示词",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (favorite.reverseError.isNotBlank()) {
                    Text(
                        text = "上次失败：${favorite.reverseError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (readOnly) {
                    SelectionContainer {
                        Text(
                            text = favorite.prompt.ifBlank { "（还没有提示词）" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("正向提示词") },
                        minLines = 8,
                        maxLines = 16,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "保存后卡片会移到「有词区」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (readOnly) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(
                    onClick = { onSave(text.trim()) },
                    enabled = text.isNotBlank(),
                ) { Text("保存") }
            }
        },
        dismissButton = {
            if (!readOnly) TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Confirmation for moves that discard or re-do work. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** One-line message row used above the bottom bar. */
@Composable
fun MessageBar(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("关闭") }
    }
}
