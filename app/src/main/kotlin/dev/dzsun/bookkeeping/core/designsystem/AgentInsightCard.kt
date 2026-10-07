package dev.dzsun.bookkeeping.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast

/**
 * ============================================================
 *  Agent 主动建议卡片（与 HTML 原型 .ins 1:1 对应）
 *  - 禅意：右上角朱砂印章（警/荐/察）
 *  - 杂志：右上角超大斜体刊号
 *  - 暗夜：四角香槟金 Art Deco 角饰
 *  - 极简：无边框灰底圆角
 * ============================================================
 */

enum class InsightType(val tag: String, val seal: String) {
    WARN("预警", "警"),
    INVEST("建议", "荐"),
    INFO("提醒", "察")
}

data class AgentInsight(
    val type: InsightType,
    val title: String,
    val body: String,
    /** 主行动文案，如「查看明细 →」或「复制金额 ¥8,000」 */
    val action: String,
    /** 实心按钮（复制金额类） */
    val solid: Boolean = false,
    /** 需要复制到剪贴板的文本（可选） */
    val copyText: String? = null
)

@Composable
private fun InsightType.colors(): Pair<Color, Color> {
    val p = Art.colors
    return when (this) {
        InsightType.WARN -> p.warn to p.warn.copy(alpha = 0.14f)
        InsightType.INVEST -> p.pos to p.pos.copy(alpha = 0.12f)
        InsightType.INFO -> p.chart[3] to p.chart[3].copy(alpha = 0.12f)
    }
}

@Composable
fun InsightCard(
    insight: AgentInsight,
    index: Int,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {}
) {
    val p = Art.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val (tagColor, tagBg) = insight.type.colors()

    // 暗夜：Art Deco 金角饰
    val luxeCorners = Modifier.drawBehind {
        if (p.dark) {
            val l = 14.dp.toPx()
            val off = 7.dp.toPx()
            val s = 1.dp.toPx()
            val c = p.accent
            // 左上
            drawRect(c, Offset(off, off), Size(l, s))
            drawRect(c, Offset(off, off), Size(s, l))
            // 右下
            drawRect(c, Offset(size.width - off - l, size.height - off - s), Size(l, s))
            drawRect(c, Offset(size.width - off - s, size.height - off - l), Size(s, l))
        }
    }

    Box(
        modifier = modifier
            .width(300.dp)
            .clip(RoundedCornerShape(p.radiusL))
            .background(p.card)
            .then(
                if (p.dark || Art.style != ArtStyle.MINIMAL)
                    Modifier.border(1.dp, if (p.dark) p.line else p.line, RoundedCornerShape(p.radiusL))
                else Modifier
            )
            .then(luxeCorners)
            .clickable(onClick = onAction)
            .padding(24.dp)
    ) {
        // 杂志主题：超大斜体刊号
        if (Art.style == ArtStyle.EDITORIAL) {
            Text(
                "${index + 1}",
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp),
                style = TextStyle(
                    fontFamily = Art.type.num, fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium, fontSize = 44.sp
                ),
                color = p.ink.copy(alpha = 0.08f)
            )
        }
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 类型胶囊
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (p.dark) Color.Transparent else tagBg)
                        .then(if (p.dark) Modifier.border(1.dp, tagColor.copy(alpha = 0.4f), RoundedCornerShape(999.dp)) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        insight.type.tag,
                        style = TextStyle(fontSize = 11.sp, letterSpacing = 2.5.sp, fontFamily = Art.type.body),
                        color = tagColor
                    )
                }
                // 禅意：朱砂印章
                if (Art.style == ArtStyle.ZEN) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .rotate(-5f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(p.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            insight.type.seal,
                            style = TextStyle(fontFamily = Art.type.display, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                            color = Color(0xFFF5F1E8)
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                insight.title,
                style = TextStyle(
                    fontFamily = Art.type.display, fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp, letterSpacing = 0.5.sp
                ),
                color = p.ink
            )

            Spacer(Modifier.height(8.dp))

            Text(
                insight.body,
                style = TextStyle(fontSize = 13.5.sp, lineHeight = 23.sp, fontFamily = Art.type.body),
                color = p.ink2,
                modifier = Modifier.heightIn(min = 68.dp)
            )

            Spacer(Modifier.height(14.dp))

            val doAction = {
                if (insight.copyText != null) {
                    clipboard.setText(AnnotatedString(insight.copyText))
                    Toast.makeText(context, "金额已复制，去理财 App 粘贴即可", Toast.LENGTH_SHORT).show()
                }
                onAction()
            }
            if (insight.solid) {
                // 实心行动钮（复制金额）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (p.dark) p.accent else p.ink)
                        .clickable(onClick = doAction)
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(
                        insight.action,
                        style = TextStyle(fontSize = 12.5.sp, letterSpacing = 1.sp, fontFamily = Art.type.body),
                        color = if (p.dark) Color(0xFF131109) else p.bg
                    )
                }
            } else {
                Text(
                    insight.action,
                    style = TextStyle(fontSize = 12.5.sp, letterSpacing = 1.sp, fontFamily = Art.type.body),
                    color = p.accent,
                    modifier = Modifier.clickable(onClick = doAction)
                )
            }
        }
    }
}

/** 首页演示数据（生产环境由 Agent 引擎产出） */
val demoInsights = listOf(
    AgentInsight(
        InsightType.WARN, "餐饮超支 14%",
        "本月餐饮已花 ¥2,840，超出预算 14%，外卖占了六成。我标出了 3 家高频商家，要不要一起看看？",
        "查看明细 →"
    ),
    AgentInsight(
        InsightType.INVEST, "闲钱别躺着",
        "活期里躺着 ¥23,000。按你的风险偏好，建议先转 ¥8,000 去余额宝，随用随取。",
        "复制金额 ¥8,000", solid = true, copyText = "8000"
    ),
    AgentInsight(
        InsightType.INFO, "一个漏财小洞",
        "「健身环会员」连续 3 个月扣费 ¥30，但你一次都没打开过。建议这个月底前取消。",
        "稍后处理 →"
    )
)
