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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 明细行编辑器：描述 + 可选金额，可增删。
 *
 * **刻意不做「明细之和 == 总额」的校验**——折扣、税、抹零都会让两者不等，
 * 明细只描述「买了什么」，记账金额以分录为准。
 */
@Composable
fun ItemsEditor(
    items: List<ItemLine>,
    onItemChange: (Int, ItemLine) -> Unit,
    onItemAdd: () -> Unit,
    onItemRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            "明细（可选）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        items.forEachIndexed { index, line ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = line.description,
                    onValueChange = { onItemChange(index, line.copy(description = it)) },
                    label = { Text("买了什么") },
                    modifier = Modifier.weight(1.6f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = line.amountText,
                    onValueChange = { onItemChange(index, line.copy(amountText = it)) },
                    label = { Text("金额") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(onClick = { onItemRemove(index) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "删除这行明细",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        TextButton(onClick = onItemAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("加一行")
        }
        Text(
            "小票上的「买了什么」。金额可空；明细之和不必等于总额。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}

/** 地点输入：金额和商户都记不住时的最后一个锚点。 */
@Composable
fun PlaceField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("地点（可选）") },
        placeholder = { Text("比如「国贸店」「公司楼下」") },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
    )
}
