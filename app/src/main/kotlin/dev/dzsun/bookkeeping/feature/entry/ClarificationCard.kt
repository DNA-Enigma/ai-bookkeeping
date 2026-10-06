package dev.dzsun.bookkeeping.feature.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dzsun.bookkeeping.core.network.PendingClarification

/**
 * 澄清渲染器：只吃服务端（或本地合成）的 [PendingClarification]，
 * 不写死表单。选项、文案、是否阻断全部由数据驱动。
 */
@Composable
fun ClarificationCard(
    clarification: PendingClarification,
    onAnswer: (optionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedId by remember(clarification.questionId) {
        mutableStateOf(clarification.options.firstOrNull()?.id.orEmpty())
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    clarification.question,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                clarification.options.forEach { option ->
                    FilterChip(
                        selected = selectedId == option.id,
                        onClick = { selectedId = option.id },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onAnswer(selectedId) },
                enabled = selectedId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("按「${clarification.options.firstOrNull { it.id == selectedId }?.label ?: "所选"}」继续")
            }
            if (clarification.blocking) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "这一步必须确认，账目方向错了会污染账本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** 字段改动摘要：把 `edits[]` 摊成人能看懂的一行，确认入账前展示。 */
@Composable
fun FieldEditSummary(edits: List<dev.dzsun.bookkeeping.core.network.FieldEdit>, modifier: Modifier = Modifier) {
    if (edits.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("你改了 ${edits.size} 处，会作为修正反馈回报", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            edits.forEach { edit ->
                Text(
                    "· ${edit.field}: ${edit.from ?: "空"} → ${edit.to}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 低置信度标记，放在卡片角上。 */
@Composable
fun NeedsConfirmChip(modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        label = { Text("待确认") },
        modifier = modifier,
    )
}
