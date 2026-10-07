package dev.dzsun.bookkeeping.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dzsun.bookkeeping.R

/**
 * ============================================================
 *  账房 · 艺术主题系统（与 HTML 原型 index.html 1:1 对应）
 * ============================================================
 *  四套主题：东方禅意 / 杂志印象 / 现代极简 / 暗夜奢华
 *  依赖（app 模块 build.gradle）：
 *    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.5")
 *  并在 res/values/font_certs.xml 中加入 Google Play 字体证书数组
 *  com_google_android_gms_fonts_certs（Android Studio 可自动生成）。
 * ============================================================
 */

enum class ArtStyle(val label: String, val sub: String, val previewGlyph: String) {
    ZEN("东方禅意", "宣纸 · 墨色 · 朱砂印", "永"),
    EDITORIAL("杂志印象", "衬线刊头 · 编辑排版", "Aa"),
    MINIMAL("现代极简", "大留白 · 数字主角", "Aa"),
    LUXE("暗夜奢华", "深空黑 · 香槟金线", "Aa")
}

/* ---------------- 调色板 ---------------- */

@Immutable
data class ArtPalette(
    val bg: Color, val surface: Color, val card: Color,
    val ink: Color, val ink2: Color, val ink3: Color,
    val line: Color, val line2: Color,
    val accent: Color, val accent2: Color, val pos: Color,
    val warn: Color,
    val chart: List<Color>,          // c1..c6 图表色
    val radius: Dp, val radiusL: Dp,
    val stageAsCard: Boolean,        // 极简：数字舞台为色块卡片；其余：发丝线通栏
    val showSecLine: Boolean,        // 极简：章节无分隔线
    val dark: Boolean
)

private val ZenPalette = ArtPalette(
    bg = Color(0xFFF3EFE5), surface = Color(0xFFFAF7EE), card = Color(0xFFFBF9F2),
    ink = Color(0xFF2A261E), ink2 = Color(0xFF6D6759), ink3 = Color(0xFFA39B88),
    line = Color(0x2E2A261E), line2 = Color(0x142A261E),
    accent = Color(0xFFB23A30), accent2 = Color(0xFF5F7161), pos = Color(0xFF5F7161),
    warn = Color(0xFFA4552E),
    chart = listOf(Color(0xFFB23A30), Color(0xFF5F7161), Color(0xFFA8842C),
        Color(0xFF3D4E5C), Color(0xFF8A8272), Color(0xFF2A261E)),
    radius = 3.dp, radiusL = 3.dp,
    stageAsCard = false, showSecLine = true, dark = false
)

private val EditorialPalette = ArtPalette(
    bg = Color(0xFFFAF6EE), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF),
    ink = Color(0xFF1A1815), ink2 = Color(0xFF5C564B), ink3 = Color(0xFFA89F8E),
    line = Color(0x291A1815), line2 = Color(0x121A1815),
    accent = Color(0xFFB56A4C), accent2 = Color(0xFF6F8095), pos = Color(0xFF6F8095),
    warn = Color(0xFFA4552E),
    chart = listOf(Color(0xFFB56A4C), Color(0xFF6F8095), Color(0xFF9AA88F),
        Color(0xFFC9A227), Color(0xFF7A6A5A), Color(0xFF1A1815)),
    radius = 2.dp, radiusL = 2.dp,
    stageAsCard = false, showSecLine = true, dark = false
)

private val MinimalPalette = ArtPalette(
    bg = Color(0xFFFFFFFF), surface = Color(0xFFF5F5F7), card = Color(0xFFF5F5F7),
    ink = Color(0xFF1D1D1F), ink2 = Color(0xFF6E6E73), ink3 = Color(0xFFAEAEB2),
    line = Color(0x1A000000), line2 = Color(0x0D000000),
    accent = Color(0xFF0071E3), accent2 = Color(0xFF30D158), pos = Color(0xFF0A7D31),
    warn = Color(0xFFA4552E),
    chart = listOf(Color(0xFF0071E3), Color(0xFF5E5CE6), Color(0xFF32ADE6),
        Color(0xFF64D2FF), Color(0xFF98989D), Color(0xFF1D1D1F)),
    radius = 16.dp, radiusL = 20.dp,
    stageAsCard = true, showSecLine = false, dark = false
)

private val LuxePalette = ArtPalette(
    bg = Color(0xFF0D0C0A), surface = Color(0xFF14120D), card = Color(0xFF16140F),
    ink = Color(0xFFECE3D1), ink2 = Color(0xFFA89D88), ink3 = Color(0xFF6F6655),
    line = Color(0x42C8A45F), line2 = Color(0x1AC8A45F),
    accent = Color(0xFFC8A45F), accent2 = Color(0xFF8A9A7C), pos = Color(0xFF9DB08E),
    warn = Color(0xFFD09A6A),
    chart = listOf(Color(0xFFC8A45F), Color(0xFF8F7340), Color(0xFFE6D3A3),
        Color(0xFF7A8B7C), Color(0xFF5C5648), Color(0xFFECE3D1)),
    radius = 2.dp, radiusL = 2.dp,
    stageAsCard = false, showSecLine = true, dark = true
)

fun ArtStyle.palette(): ArtPalette = when (this) {
    ArtStyle.ZEN -> ZenPalette
    ArtStyle.EDITORIAL -> EditorialPalette
    ArtStyle.MINIMAL -> MinimalPalette
    ArtStyle.LUXE -> LuxePalette
}

/* ---------------- 字体 ---------------- */

object ArtFonts {
    private val provider = GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs
    )

    private fun gf(name: String, vararg styles: Pair<FontWeight, FontStyle>): FontFamily =
        FontFamily(styles.map { (w, s) ->
            Font(googleFont = GoogleFont(name), fontProvider = provider, weight = w, style = s)
        })

    val NotoSerifSC by lazy {
        gf("Noto Serif SC", FontWeight.Normal to FontStyle.Normal, FontWeight.Medium to FontStyle.Normal,
            FontWeight.SemiBold to FontStyle.Normal, FontWeight.Bold to FontStyle.Normal,
            FontWeight.Black to FontStyle.Normal)
    }
    val NotoSansSC by lazy {
        gf("Noto Sans SC", FontWeight.Light to FontStyle.Normal, FontWeight.Normal to FontStyle.Normal,
            FontWeight.Medium to FontStyle.Normal, FontWeight.Bold to FontStyle.Normal)
    }
    val Playfair by lazy {
        gf("Playfair Display", FontWeight.Normal to FontStyle.Normal, FontWeight.Medium to FontStyle.Normal,
            FontWeight.SemiBold to FontStyle.Normal, FontWeight.Bold to FontStyle.Normal,
            FontWeight.Medium to FontStyle.Italic, FontWeight.SemiBold to FontStyle.Italic)
    }
    val Inter by lazy {
        gf("Inter", FontWeight.Light to FontStyle.Normal, FontWeight.Normal to FontStyle.Normal,
            FontWeight.Medium to FontStyle.Normal, FontWeight.SemiBold to FontStyle.Normal,
            FontWeight.Bold to FontStyle.Normal)
    }
    val Cormorant by lazy {
        gf("Cormorant Garamond", FontWeight.Normal to FontStyle.Normal, FontWeight.Medium to FontStyle.Normal,
            FontWeight.SemiBold to FontStyle.Normal, FontWeight.Medium to FontStyle.Italic,
            FontWeight.SemiBold to FontStyle.Italic)
    }
}

@Immutable
data class ArtType(
    val display: FontFamily, val body: FontFamily, val num: FontFamily,
    val heroWeight: FontWeight, val numWeight: FontWeight,
    val numLetterSpacingSp: Float,
    val greetingItalic: Boolean     // 杂志/暗夜：问候名用斜体；禅意/极简：用强调色
)

private fun ArtStyle.type(): ArtType = when (this) {
    ArtStyle.ZEN -> ArtType(ArtFonts.NotoSerifSC, ArtFonts.NotoSerifSC, ArtFonts.NotoSerifSC,
        FontWeight.SemiBold, FontWeight.Bold, 0f, greetingItalic = false)
    ArtStyle.EDITORIAL -> ArtType(ArtFonts.Playfair, ArtFonts.NotoSansSC, ArtFonts.Playfair,
        FontWeight.SemiBold, FontWeight.SemiBold, 0f, greetingItalic = true)
    ArtStyle.MINIMAL -> ArtType(ArtFonts.Inter, ArtFonts.Inter, ArtFonts.Inter,
        FontWeight.Bold, FontWeight.Light, -0.5f, greetingItalic = false)
    ArtStyle.LUXE -> ArtType(ArtFonts.Cormorant, ArtFonts.NotoSerifSC, ArtFonts.Cormorant,
        FontWeight.Medium, FontWeight.Medium, 0.5f, greetingItalic = true)
}

/* ---------------- CompositionLocal ---------------- */

val LocalArtStyle = staticCompositionLocalOf { ArtStyle.ZEN }
val LocalArtPalette = staticCompositionLocalOf { ZenPalette }
val LocalArtType = staticCompositionLocalOf<ArtType> {
    ArtType(FontFamily.Serif, FontFamily.Serif, FontFamily.Serif,
        FontWeight.SemiBold, FontWeight.Bold, 0f, false)
}

object Art {
    val style: ArtStyle @Composable get() = LocalArtStyle.current
    val colors: ArtPalette @Composable get() = LocalArtPalette.current
    val type: ArtType @Composable get() = LocalArtType.current
}

/**
 * 全局主题状态。生产环境替换为 DataStore -backed Repository，
 * 这里用可观察状态保证设置页切换即全 App 实时换肤。
 */
object ArtThemeState {
    var current by mutableStateOf(ArtStyle.ZEN)
}

@Composable
fun ArtTheme(style: ArtStyle, content: @Composable () -> Unit) {
    val p = style.palette()
    val t = style.type()
    val m3 = if (p.dark) darkColorScheme(
        primary = p.accent, onPrimary = Color(0xFF131109),
        background = p.bg, onBackground = p.ink,
        surface = p.surface, onSurface = p.ink,
        surfaceVariant = p.card, onSurfaceVariant = p.ink2,
        outline = p.line, outlineVariant = p.line2
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White,
        background = p.bg, onBackground = p.ink,
        surface = p.surface, onSurface = p.ink,
        surfaceVariant = p.card, onSurfaceVariant = p.ink2,
        outline = p.line, outlineVariant = p.line2
    )
    CompositionLocalProvider(
        LocalArtStyle provides style,
        LocalArtPalette provides p,
        LocalArtType provides t
    ) {
        MaterialTheme(
            colorScheme = m3,
            shapes = Shapes(
                small = RoundedCornerShape(p.radius),
                medium = RoundedCornerShape(p.radiusL),
                large = RoundedCornerShape(p.radiusL)
            ),
            content = content
        )
    }
}

/* ============================================================
 *  共享组件（四个页面复用）
 * ============================================================ */

/** 发丝线 */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Art.colors.line, thickness: Dp = 1.dp) {
    Box(modifier.fillMaxWidth().height(thickness).background(color))
}

/** 章节刊号：禅意=壹贰叁，杂志/极简=01，暗夜=I II III */
@Composable
fun SectionNo(no: Int, modifier: Modifier = Modifier) {
    val zh = listOf("壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌")
    val rm = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII")
    val (txt, style) = when (Art.style) {
        ArtStyle.ZEN -> zh[(no - 1).coerceIn(0, 7)] to TextStyle(
            fontFamily = Art.type.display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        ArtStyle.LUXE -> rm[(no - 1).coerceIn(0, 7)] to TextStyle(
            fontFamily = Art.type.num, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 2.sp)
        ArtStyle.EDITORIAL -> "%02d".format(no) to TextStyle(
            fontFamily = ArtTypeDefaults.playfairFallback(), fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Medium, fontSize = 17.sp)
        ArtStyle.MINIMAL -> "%02d".format(no) to TextStyle(
            fontFamily = Art.type.num, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
    Text(txt, modifier = modifier, style = style, color = Art.colors.accent)
}

private object ArtTypeDefaults {
    @Composable fun playfairFallback() = Art.type.num
}

/**
 * 章节刊头：刊号 + 标题 + （禅意竖排点缀）+ 发丝线 + 可选「全部 →」
 */
@Composable
fun SectionHeader(
    no: Int,
    title: String,
    vertical: String? = null,
    onMore: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionNo(no)
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            style = TextStyle(
                fontFamily = Art.type.display, fontWeight = FontWeight.SemiBold,
                fontSize = 21.sp, letterSpacing = 1.sp
            ),
            color = Art.colors.ink
        )
        if (vertical != null && Art.style == ArtStyle.ZEN) {
            Spacer(Modifier.width(14.dp))
            // 竖排点缀：逐字纵向堆叠
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                vertical.forEach { ch ->
                    Text(
                        ch.toString(),
                        style = TextStyle(fontFamily = Art.type.display, fontSize = 11.sp, letterSpacing = 2.sp),
                        color = Art.colors.ink3, lineHeight = 14.sp
                    )
                }
            }
        }
        if (Art.colors.showSecLine) {
            Spacer(Modifier.width(18.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Art.colors.line))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onMore != null) {
            Spacer(Modifier.width(14.dp))
            Text(
                "全部 →",
                style = TextStyle(fontSize = 12.sp, letterSpacing = 1.5.sp),
                color = Art.colors.ink2,
                modifier = Modifier.clickable(onClick = onMore)
            )
        }
    }
}

/** 印章徽标：禅意=朱砂方印，杂志=墨底斜体，极简=圆角黑块，暗夜=描金框 */
@Composable
fun SealBadge(char: String, size: Dp = 34.dp, accentFill: Boolean = true) {
    val p = Art.colors
    val bg: Color
    val fg: Color
    val rotate: Float
    val shape = RoundedCornerShape(p.radius)
    when (Art.style) {
        ArtStyle.ZEN -> { bg = if (accentFill) p.accent else p.ink; fg = Color(0xFFF5F1E8); rotate = -4f }
        ArtStyle.EDITORIAL -> { bg = p.ink; fg = p.bg; rotate = 0f }
        ArtStyle.MINIMAL -> { bg = p.ink; fg = Color.White; rotate = 0f }
        ArtStyle.LUXE -> { bg = Color.Transparent; fg = p.accent; rotate = 0f }
    }
    Box(
        modifier = Modifier
            .size(size)
            .rotate(rotate)
            .clip(shape)
            .background(bg)
            .then(
                if (Art.style == ArtStyle.LUXE)
                    Modifier.border(1.dp, p.accent, shape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            char,
            style = TextStyle(
                fontFamily = Art.type.display,
                fontWeight = FontWeight.Bold,
                fontStyle = if (Art.style == ArtStyle.EDITORIAL) FontStyle.Italic else FontStyle.Normal,
                fontSize = (size.value * 0.5).sp
            ),
            color = fg
        )
    }
}

/** 主题化开关 */
@Composable
fun ArtSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Art.colors.accent,
            checkedThumbColor = Color.White,
            uncheckedTrackColor = Art.colors.line,
            uncheckedThumbColor = Art.colors.bg,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

/** 主题化胶囊 Chip：未选中=发丝线描边，选中=墨色（暗夜=香槟金）实心 */
@Composable
fun ArtChip(text: String, selected: Boolean, onClick: () -> Unit, accentText: Boolean = false) {
    val p = Art.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) (if (p.dark) p.accent else p.ink) else Color.Transparent)
            .border(
                1.dp,
                if (selected) Color.Transparent else p.line,
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 13.sp, letterSpacing = 1.sp, fontFamily = Art.type.body),
            color = when {
                selected -> if (p.dark) Color(0xFF131109) else p.bg
                accentText -> p.pos
                else -> p.ink2
            }
        )
    }
}

/** 金额文本：整数大、小数小，等宽数字 */
@Composable
fun AmountText(
    integer: String,
    decimal: String = "",
    currency: String = "¥",
    fontSize: Int = 40,
    color: Color = Art.colors.ink,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            currency,
            style = TextStyle(
                fontFamily = Art.type.num, fontSize = (fontSize * 0.42).sp,
                fontWeight = FontWeight.Normal
            ),
            color = Art.colors.ink2,
            modifier = Modifier.padding(end = 4.dp, bottom = (fontSize * 0.10).dp)
        )
        Text(
            integer,
            style = TextStyle(
                fontFamily = Art.type.num, fontSize = fontSize.sp,
                fontWeight = Art.type.numWeight,
                letterSpacing = Art.type.numLetterSpacingSp.sp,
                fontFeatureSettings = "tnum"
            ),
            color = color
        )
        if (decimal.isNotEmpty()) {
            Text(
                decimal,
                style = TextStyle(
                    fontFamily = Art.type.num, fontSize = (fontSize * 0.42).sp,
                    fontWeight = FontWeight.Normal
                ),
                color = Art.colors.ink3,
                modifier = Modifier.padding(bottom = (fontSize * 0.10).dp)
            )
        }
    }
}
