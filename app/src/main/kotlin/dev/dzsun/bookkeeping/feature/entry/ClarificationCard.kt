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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.dzsun.bookkeeping.core.network.ClarificationOption
import dev.dzsun.bookkeeping.core.network.PendingClarification

/**
 * 澄清渲染器：只吃服务端（或本地合成）的 [PendingClarification]，
 * 不写死表单。选项、文案、是否阻断全部由数据驱动。
 *
 * 服务端下发的澄清**可以没有选项**（实测就是 `options: []`）。
 * 那时 [freeText] 是契约里唯一可用的答复路径，所以这里必须留自由输入——
 * 否则按钮永远灰着、任务又 `blocking`，用户和任务一起卡死。
 */
@Composable
fun ClarificationCard(
    clarification: PendingClarification,
    onAnswer: (optionId: String?, freeText: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasOptions = clarification.options.isNotEmpty()
    var selectedId by remember(clarification.questionId) {
        mutableStateOf(clarification.options.firstOrNull()?.id.orEmpty())
    }
    var freeText by remember(clarification.questionId) { mutableStateOf("") }
    val canSubmit = canAnswerClarification(clarification.options, selectedId, freeText)

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
            if (hasOptions) {
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
            } else {
                OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("补充说明") },
                    placeholder = { Text("把问题里要的信息写在这里，比如「38 元，微信」") },
                    minLines = 2,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (hasOptions) onAnswer(selectedId, null) else onAnswer(null, freeText.trim())
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (hasOptions) {
                        "按「${clarification.options.firstOrNull { it.id == selectedId }?.label ?: "所选"}」继续"
                    } else {
                        "继续"
                    },
                )
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

/**
 * 低置信时的核对提示。
 *
 * 说的是**该核对什么**，而不是只报一个数字：「置信度 0.62」对用户没有意义，
 * 「识别置信度较低，请核对金额和分类」才是他能照着做的事。
 *
 * [weakestField] 是分字段置信度里最低的那个（服务端当前多半给不出）。
 * 有它就精确到那一项，没有就退回通用文案——**不编造字段名**。
 */
@Composable
fun LowConfidenceHint(
    weakestField: String? = null,
    modifier: Modifier = Modifier,
) {
    val what = when (weakestField?.lowercase()) {
        "amount", "金额" -> "金额"
        "merchant", "payee", "商家" -> "商家"
        "category", "分类" -> "分类"
        "datetime", "date", "时间", "日期" -> "时间"
        "payment_method", "支付方式" -> "支付方式"
        "direction", "收支方向" -> "收支方向"
        "note", "notes", "备注" -> "备注"
        else -> null
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (what != null) "识别置信度较低，请重点核对$what" else "识别置信度较低，请核对金额和分类",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
