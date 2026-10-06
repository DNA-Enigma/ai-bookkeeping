package dev.dzsun.bookkeeping.feature.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dzsun.bookkeeping.core.designsystem.IncomeGreen
import kotlinx.coroutines.delay

/** 撤销窗口的长度。够看清楚发生了什么、够决定要不要反悔，又不至于挡着下一笔。 */
private const val UNDO_WINDOW_MS = 5_000L

/**
 * 自动入账后的那一条：「✓ 已入账 ¥38.50 星巴克 · 撤销」。
 *
 * **为什么不用全屏成功动效**：这条路上用户**没有按过任何按钮**，账是他没看见的时候
 * 记上的。全屏动效会挡住界面、还自己消失，等于把唯一的反悔机会也一起收走。
 * 留一条能点撤销的常驻提示，才是「直接入账」该配的交代。
 *
 * [AutoSavedNotice.undoAvailable] 为 false 时不渲染撤销按钮——没有这个能力却摆一个
 * 点不动的按钮，比不摆更让人恼火。
 */
@Composable
fun AutoSavedBar(
    notice: AutoSavedNotice,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 窗口过了就自己收掉；用户点了撤销则立刻收掉，不会残留
    LaunchedEffect(notice) {
        delay(UNDO_WINDOW_MS)
        onDismiss()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(IncomeGreen.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = IncomeGreen,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "已入账 ${notice.label}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (notice.undoAvailable) {
            TextButton(onClick = onUndo) { Text("撤销") }
        } else {
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}
