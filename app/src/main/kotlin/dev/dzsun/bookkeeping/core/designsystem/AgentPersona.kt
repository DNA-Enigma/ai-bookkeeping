package dev.dzsun.bookkeeping.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Agent 人设系统。
 *
 * 四种预设人设，影响所有文案生成、问候语、建议语气、空状态文案。
 * 默认「贴心管家」。
 */
enum class AgentPersona(
    val displayName: String,
    val subtitle: String,
    val icon: ImageVector,
    val accentColor: Color,
    val greetingStyle: GreetingStyle,
) {
    PROFESSIONAL(
        displayName = "专业顾问",
        subtitle = "数据驱动，严谨客观",
        icon = Icons.Default.AccountBalance,
        accentColor = Color(0xFF475569),
        greetingStyle = GreetingStyle.FORMAL,
    ),
    BUTLER(
        displayName = "贴心管家",
        subtitle = "温暖陪伴，主动关怀",
        icon = Icons.Default.SentimentSatisfied,
        accentColor = Color(0xFF2563EB),
        greetingStyle = GreetingStyle.WARM,
    ),
    SASSY(
        displayName = "毒舌闺蜜",
        subtitle = "俏皮吐槽，轻松互动",
        icon = Icons.Default.Face,
        accentColor = Color(0xFF7C3AED),
        greetingStyle = GreetingStyle.PLAYFUL,
    ),
    MINIMAL(
        displayName = "极简助手",
        subtitle = "干脆利落，不说废话",
        icon = Icons.Default.TextSnippet,
        accentColor = Color(0xFF0F172A),
        greetingStyle = GreetingStyle.DIRECT,
    ),
}

enum class GreetingStyle {
    FORMAL, WARM, PLAYFUL, DIRECT
}

/**
 * 文案模板库。所有人设共用同一套模板结构，只换措辞。
 *
 * 模板使用 {变量} 占位，由调用方填充。
 */
object AgentCopy {

    // ====== 首页问候语 ======
    fun greeting(persona: AgentPersona, hour: Int, todayExpense: String): String = when (persona) {
        AgentPersona.PROFESSIONAL -> when {
            hour < 12 -> "上午好。今日已支出 $todayExpense，请留意预算执行进度。"
            hour < 18 -> "下午好。今日已支出 $todayExpense，建议关注非必要开支。"
            else -> "晚上好。今日共支出 $todayExpense，建议回顾今日消费结构。"
        }
        AgentPersona.BUTLER -> when {
            hour < 12 -> "早上好！今天已经花了 $todayExpense，记得吃早餐哦 ☀️"
            hour < 18 -> "下午好～今天花了 $todayExpense，要不要喝杯咖啡休息一下？"
            else -> "晚上好！今天一共花了 $todayExpense，辛苦了一天，早点休息 🌙"
        }
        AgentPersona.SASSY -> when {
            hour < 12 -> "早啊！$todayExpense 已经没了，你今天花钱的速度比起床还快 😏"
            hour < 18 -> "下午好～$todayExpense 飞走了，你的手是漏斗吗？"
            else -> "晚上好！今天花掉了 $todayExpense，钱包哭了吗？没有，因为它已经麻木了 🌙"
        }
        AgentPersona.MINIMAL -> when {
            hour < 12 -> "早。今日支出：$todayExpense"
            hour < 18 -> "下午。今日支出：$todayExpense"
            else -> "晚。今日支出：$todayExpense"
        }
    }

    // ====== 消费即时反馈 ======
    fun instantFeedback(
        persona: AgentPersona,
        category: String,
        amount: String,
        monthTotal: String,
        budgetRemaining: String,
        budgetPercent: Int,
    ): String = when (persona) {
        AgentPersona.PROFESSIONAL ->
            "$category 支出 $amount 已入账。本月该类累计 $monthTotal，预算剩余 $budgetRemaining（$budgetPercent%）。"
        AgentPersona.BUTLER ->
            "已帮你记下 $category $amount～本月$category 已经花了 $monthTotal，预算还剩 $budgetRemaining，还有 $budgetPercent% 的空间 ✨"
        AgentPersona.SASSY ->
            "$category $amount，入账！本月$category 已经 $monthTotal 了，预算只剩 $budgetRemaining，你悠着点啊 😅"
        AgentPersona.MINIMAL ->
            "$category +$amount。本月累计 $monthTotal，预算余 $budgetRemaining。"
    }

    // ====== 预算预警 ======
    fun budgetWarning(
        persona: AgentPersona,
        category: String,
        remainingPercent: Int,
        daysLeft: Int,
    ): String = when (persona) {
        AgentPersona.PROFESSIONAL ->
            "警告：$category 预算已使用 ${100 - remainingPercent}%，剩余 $remainingPercent%。按当前进度，预计月末将出现赤字。"
        AgentPersona.BUTLER ->
            "$category 预算只剩 $remainingPercent% 了，还有 $daysLeft 天才到月底～这周稍微控制一下就好 💪"
        AgentPersona.SASSY ->
            "$category 预算只剩 $remainingPercent%？！还有 $daysLeft 天呢，你是打算最后几天喝西北风吗？🫠"
        AgentPersona.MINIMAL ->
            "$category 预算预警：剩余 $remainingPercent%，$daysLeft 天。"
    }

    // ====== 异常消费提醒 ======
    fun abnormalSpending(
        persona: AgentPersona,
        amount: String,
        avgMultiple: String,
        merchant: String,
    ): String = when (persona) {
        AgentPersona.PROFESSIONAL ->
            "检测到异常支出：$merchant $amount，超出您日均单笔均值 $avgMultiple 倍。请确认该笔交易。"
        AgentPersona.BUTLER ->
            "刚有一笔 $merchant $amount 的支出，比平时高了不少呢～是你自己花的吗？确认一下吧 🔍"
        AgentPersona.SASSY ->
            "$amount？！你平时单笔也就零头，这次直接翻了 $avgMultiple 倍！被盗刷了还是冲动消费了？😱"
        AgentPersona.MINIMAL ->
            "异常：$merchant $amount，超均值 $avgMultiple 倍。"
    }

    // ====== 每日简报 ======
    fun dailyBrief(
        persona: AgentPersona,
        todayTotal: String,
        topCategory: String,
        topAmount: String,
        budgetStatus: String,
    ): String = when (persona) {
        AgentPersona.PROFESSIONAL ->
            "今日支出 $todayTotal，主要投向 $topCategory（$topAmount）。$budgetStatus"
        AgentPersona.BUTLER ->
            "今天花了 $todayTotal，大头在 $topCategory（$topAmount）。$budgetStatus 明天继续加油 💪"
        AgentPersona.SASSY ->
            "今天 $todayTotal 没了，$topCategory 就吞了 $topAmount。$budgetStatus 明天再浪🫠"
        AgentPersona.MINIMAL ->
            "今日：$todayTotal，$topCategory $topAmount。$budgetStatus"
    }

    // ====== 周末复盘 ======
    fun weeklyReview(
        persona: AgentPersona,
        weekTotal: String,
        weekDelta: String,
        weekDeltaUp: Boolean,
        topInsight: String,
    ): String = when (persona) {
        AgentPersona.PROFESSIONAL ->
            "本周支出 $weekTotal，较上周${if (weekDeltaUp) "增加" else "减少"} $weekDelta。核心发现：$topInsight"
        AgentPersona.BUTLER ->
            "本周花了 $weekTotal，比上周${if (weekDeltaUp) "多了" else "省了"} $weekDelta！$topInsight 下周继续保持～🎉"
        AgentPersona.SASSY ->
            "本周 $weekTotal，比上周${if (weekDeltaUp) "多花" else "少花"} $weekDelta。$topInsight ${if (weekDeltaUp) "下周能不能长点心" else "继续保持，别飘"} 😏"
        AgentPersona.MINIMAL ->
            "本周：$weekTotal，环比${if (weekDeltaUp) "+" else "−"}$weekDelta。$topInsight"
    }

    // ====== 月度洞察 / 投资建议 ======
    fun monthlyInsight(
        persona: AgentPersona,
        savingRate: String,
        surplus: String,
        suggestedInvest: String,
    ): String = when (persona) {
        AgentPersona.PROFESSIONAL ->
            "本月结余率为 $savingRate，盈余 $surplus。建议将盈余的 40%（$suggestedInvest）配置至指数基金定投，以实现资产稳健增值。"
        AgentPersona.BUTLER ->
            "这个月你存下了 $surplus，结余率 $savingRate，真棒！🎉 如果把其中 $suggestedInvest 拿来定投，一年后大概能多赚好几百呢～要不要试试？"
        AgentPersona.SASSY ->
            "哟，结余 $surplus，结余率 $savingRate，居然没花光？值得表扬！😏 但钱放手里只会贬值，$suggestedInvest 定投搞起来？"
        AgentPersona.MINIMAL ->
            "结余率 $savingRate，盈余 $surplus。建议定投 $suggestedInvest。"
    }

    // ====== 投资建议详情页（MVP 版） ======
    fun investAdviceDetail(
        persona: AgentPersona,
        platformName: String,
        pros: String,
        cons: String,
        steps: List<String>,
        suggestedAmount: String,
    ): String = when (persona) {
        AgentPersona.PROFESSIONAL ->
            buildString {
                append("推荐平台：$platformName\n")
                append("优势：$pros\n")
                append("注意：$cons\n\n")
                append("操作步骤：\n")
                steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
                append("\n建议定投金额：$suggestedAmount（已复制到剪贴板）")
            }
        AgentPersona.BUTLER ->
            buildString {
                append("推荐你用 $platformName 来定投～\n")
                append("✅ $pros\n")
                append("⚠️ 要注意：$cons\n\n")
                append("操作很简单：\n")
                steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
                append("\n建议每月投 $suggestedAmount，金额已经帮你复制好啦，打开 App 粘贴就行～")
            }
        AgentPersona.SASSY ->
            buildString {
                append("给你选了 $platformName，别挑了 😏\n")
                append("好的：$pros\n")
                append("坑：$cons\n\n")
                append("操作步骤（小学生都会）：\n")
                steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
                append("\n每月投 $suggestedAmount，金额复制好了，别告诉我你不会粘贴 🫠")
            }
        AgentPersona.MINIMAL ->
            buildString {
                append("$platformName | $pros | $cons\n")
                steps.joinToString(" → ")
                append("\n定投：$suggestedAmount（已复制）")
            }
    }

    // ====== 空状态文案 ======
    fun emptyState(persona: AgentPersona): String = when (persona) {
        AgentPersona.PROFESSIONAL -> "暂无账目记录。请添加首笔交易以启动财务追踪。"
        AgentPersona.BUTLER -> "还没有账目呢～记第一笔，我就能帮你管钱啦 ✨"
        AgentPersona.SASSY -> "空空如也！你的钱包和你的账本一样干净 😏 快记一笔！"
        AgentPersona.MINIMAL -> "无数据。"
    }

    // ====== 错误/失败文案 ======
    fun errorRetry(persona: AgentPersona): String = when (persona) {
        AgentPersona.PROFESSIONAL -> "操作失败，请检查网络后重试。"
        AgentPersona.BUTLER -> "哎呀，出了点小问题，再试一次好吗？🥺"
        AgentPersona.SASSY -> "崩了！别慌，再点一次，它不敢再崩了 😤"
        AgentPersona.MINIMAL -> "失败。重试。"
    }
}

/**
 * 理财平台推荐库（MVP 版）。
 * 每个平台包含：名称、一句话介绍、优势、劣势、操作步骤、Scheme（预留）。
 */
data class InvestPlatform(
    val id: String,
    val name: String,
    val tagline: String,
    val pros: String,
    val cons: String,
    val steps: List<String>,
    val schemeUrl: String? = null, // MVP 阶段预留，V1.1 启用
)

val DEFAULT_PLATFORMS = listOf(
    InvestPlatform(
        id = "alipay",
        name = "支付宝「小荷包」",
        tagline = "和亲友一起存，自动扣款超省心",
        pros = "自动扣款、可和伴侣/朋友一起存、门槛低",
        cons = "收益率一般，资金在支付宝生态内",
        steps = listOf(
            "打开支付宝，搜索「小荷包」",
            "点击「新建荷包」，设置每月自动存入金额",
            "粘贴建议金额（已复制），确认开启自动攒",
        ),
        schemeUrl = "alipays://platformapi/startapp?appId=20000067",
    ),
    InvestPlatform(
        id = "wechat",
        name = "微信理财通「基金定投」",
        tagline = "微信内一键开启，操作简单",
        pros = "无需下载新 App、操作路径短、支持微信零钱",
        cons = "基金选择相对有限，手续费略高",
        steps = listOf(
            "打开微信 → 我 → 服务 → 理财通",
            "选择「基金定投」，搜索「沪深300」或「中证500」",
            "粘贴建议金额（已复制），设置每月扣款日",
        ),
        schemeUrl = null, // 微信 Scheme 受限，MVP 阶段不直接唤起
    ),
    InvestPlatform(
        id = "bank",
        name = "工资卡银行 App",
        tagline = "手续费最低，资金最安全",
        pros = "手续费通常最低、资金原卡原路、安全性最高",
        cons = "操作步骤较多、基金品种有限",
        steps = listOf(
            "打开你的工资卡银行 App",
            "搜索「基金定投」或「智能投顾」",
            "粘贴建议金额（已复制），选择指数型基金，设置每月扣款",
        ),
        schemeUrl = null, // 各银行 Scheme 不统一
    ),
)
