package dev.dzsun.bookkeeping.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 生活化亲和色板：暖米黄汇总卡 + 清爽蓝强调，对齐参考图的气质。
 * 浅色是主战场；深色只做可用性保底。
 */

// 主强调：参考图中央 + 号与选中态的清爽蓝
val BrandBlue = Color(0xFF4C6FFF)
val BrandBlueSoft = Color(0xFFE8EEFF)

// 汇总卡：暖米黄渐变
val CreamStart = Color(0xFFFFF6DC)
val CreamEnd = Color(0xFFFFE8A8)
val CreamTrack = Color(0xFFFFE8A8)
val CreamFill = Color(0xFFFFB020)

// 收支语义
val ExpenseOrange = Color(0xFFFF7A2F)
val IncomeGreen = Color(0xFF22B573)

// 分类图标底色（按参考图的彩色圆标）
val CatFood = Color(0xFFFF8A3D)
val CatTransport = Color(0xFF3DBE9B)
val CatShopping = Color(0xFFFFB020)
val CatHousing = Color(0xFF7B8CFF)
val CatComm = Color(0xFF5B9DFF)
val CatFun = Color(0xFFB56BFF)
val CatMedical = Color(0xFFFF6B81)
val CatEdu = Color(0xFF2BB3C0)
val CatSocial = Color(0xFFFFA940)
val CatOther = Color(0xFF9AA3B2)

private val LightScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlueSoft,
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF2B6B6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBFE9E9),
    onSecondaryContainer = Color(0xFF002020),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF5A6070),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF16181D),
    error = Color(0xFFE5484D),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9DB4FF),
    onPrimary = Color(0xFF002A77),
    primaryContainer = Color(0xFF1E3A8F),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF8FD3D3),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF124F4F),
    onSecondaryContainer = Color(0xFFBFE9E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF2A2C33),
    onSurfaceVariant = Color(0xFFC5C6D0),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    error = Color(0xFFFFB4AB),
)

@Composable
fun BookkeepingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}

/** 分类名 → 图标底色。找不到走中性灰，分类是数据不是代码，这里只做视觉映射。 */
fun categoryColor(name: String): Color = when {
    name.contains("餐") || name.contains("吃") || name.contains("食") -> CatFood
    name.contains("交通") || name.contains("打车") || name.contains("出行") -> CatTransport
    name.contains("购物") || name.contains("买") -> CatShopping
    name.contains("居住") || name.contains("房") || name.contains("水电") -> CatHousing
    name.contains("通讯") || name.contains("话费") -> CatComm
    name.contains("娱乐") || name.contains("游戏") || name.contains("旅游") -> CatFun
    name.contains("医疗") || name.contains("药") -> CatMedical
    name.contains("教育") || name.contains("书") || name.contains("学") -> CatEdu
    name.contains("人情") || name.contains("礼") -> CatSocial
    name.contains("工资") || name.contains("收入") || name.contains("奖金") -> IncomeGreen
    else -> CatOther
}
