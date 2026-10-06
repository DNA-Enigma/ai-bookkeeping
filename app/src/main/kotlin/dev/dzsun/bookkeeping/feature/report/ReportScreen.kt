package dev.dzsun.bookkeeping.feature.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.YearMonth
import dev.dzsun.bookkeeping.core.designsystem.BrandBlue
import dev.dzsun.bookkeeping.core.designsystem.CreamFill
import dev.dzsun.bookkeeping.core.designsystem.ExpenseOrange
import dev.dzsun.bookkeeping.core.designsystem.IncomeGreen
import dev.dzsun.bookkeeping.core.designsystem.categoryColor
import dev.dzsun.bookkeeping.core.money.Money
import kotlin.math.abs

/**
 * 月度报表：收支总览 → 超预算提醒 → 分类占比 → 日均与环比。
 *
 * 与「报表」标签页（[dev.dzsun.bookkeeping.feature.stats.StatsScreen]）的分工：
 * 那一页看**周期对比**（本月/近三月/今年）与趋势，这一页看**单独一个月**
 * 花在了哪里、预算还够不够。聚合口径两边共用同一组 SQL 投影，所以数字不会打架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onBack: () -> Unit,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("月度报表", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            MonthSwitcher(
                month = state.month,
                canGoNext = state.canGoNext,
                onPrevious = viewModel::onPreviousMonth,
                onNext = viewModel::onNextMonth,
            )

            val report = state.report
            if (report == null) {
                LoadingBody()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { TotalsCard(report) }
                    if (report.overspent.isNotEmpty()) {
                        item { OverspendCard(report) }
                    }
                    item { CategoryCard(report) }
                    item { DailyAndChangeCard(report) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** 月份切换。**不允许翻到未来**——未来的月份只有空数据，翻过去像是账丢了。 */
@Composable
private fun MonthSwitcher(
    month: YearMonth,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "上一个月")
        }
        Text(
            "${month.year} 年 ${month.monthValue} 月",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "下一个月",
                tint = if (canGoNext) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    // 翻不动的时候要看得出来翻不动，而不是点了没反应
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                },
            )
        }
    }
}

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 收入 / 支出 / 净额。三个都是大字，但净额单独一行——手机宽度放不下三列长金额。 */
@Composable
private fun TotalsCard(report: MonthlyReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TotalCell(
                    label = "本月收入",
                    amountMinor = report.incomeMinor,
                    currency = report.currency,
                    color = IncomeGreen,
                    modifier = Modifier.weight(1f),
                )
                TotalCell(
                    label = "本月支出",
                    amountMinor = report.expenseMinor,
                    currency = report.currency,
                    color = ExpenseOrange,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Text(
                "本月结余",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = signed(report.netMinor, report.currency),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = if (report.netMinor >= 0) IncomeGreen else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TotalCell(
    label: String,
    amountMinor: Long,
    currency: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            Money.of(amountMinor, currency).format(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

/**
 * 超预算汇总。
 *
 * 放在分类卡之前单列一张，是因为这是这一页唯一需要用户**立刻做决定**的东西——
 * 混在分类列表里，用户扫一眼占比就走了。
 */
@Composable
private fun OverspendCard(report: MonthlyReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${report.overspent.size} 个分类超了预算",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            report.overspent.forEach { line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        line.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "超 " + Money.of(line.overspendMinor, report.currency).format(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(report: MonthlyReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "钱花在哪了",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            if (report.categories.isEmpty()) {
                Text(
                    "这个月还没有支出记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            report.categories.forEachIndexed { index, line ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                CategoryRow(line, report.currency)
            }
        }
    }
}

@Composable
private fun CategoryRow(line: CategoryReportLine, currency: String) {
    val levelColor = when (line.level) {
        BudgetLevel.OVER -> MaterialTheme.colorScheme.error
        BudgetLevel.NEAR -> CreamFill
        BudgetLevel.OK -> BrandBlue
        BudgetLevel.UNSET -> categoryColor(line.name)
    }
    // 有条比例条、两个含义：设了预算看"预算用了多少"，没设预算看"占总支出多少"。
    // 下面的文案跟着切换，不让同一根条有两种读法却长得一样
    val fraction = (line.usage ?: line.share).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(categoryColor(line.name)),
            )
            Spacer(Modifier.width(10.dp))
            Text(line.name, modifier = Modifier.weight(1f))
            Text(
                Money.of(line.spentMinor, currency).format(),
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(levelColor),
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (line.usage != null) {
                    "已用 ${percent(line.usage)}"
                } else {
                    "占 ${percent(line.share)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (line.level == BudgetLevel.OVER) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.weight(1f))
            BudgetNote(line, currency)
        }
    }
}

/** 右侧那句预算说明。三档各自说人话，不共用一句含糊的「预算」。 */
@Composable
private fun BudgetNote(line: CategoryReportLine, currency: String) {
    val budget = line.budgetMinor
    when {
        budget == null -> Text(
            "未设预算",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        line.level == BudgetLevel.OVER -> Text(
            "预算 ${Money.of(budget, currency).format()} · 已超",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )

        line.level == BudgetLevel.NEAR -> Text(
            "预算 ${Money.of(budget, currency).format()} · 接近",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = CreamFill,
        )

        else -> Text(
            "预算 ${Money.of(budget, currency).format()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DailyAndChangeCard(report: MonthlyReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "日均支出",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 分母为 0（未来的月份）时显示「—」：显示 ¥0 会读成"花得很少"
                        if (report.daysInAverage > 0) {
                            Money.of(report.dailyAverageMinor, report.currency).format()
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (report.daysInAverage > 0) {
                        Text(
                            "按 ${report.daysInAverage} 天算",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "比上月",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    val change = report.changeRatio
                    Text(
                        text = change?.let {
                            (if (it >= 0) "+" else "−") + percent(abs(it))
                        } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            change == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            change > 0 -> MaterialTheme.colorScheme.error
                            change < 0 -> IncomeGreen
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        // 上月没有支出时环比是算不出来的，这里如实说，
                        // 而不是把除数当 1 硬凑一个百分比
                        if (change == null) "上月没有支出" else "支出同比变化",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun percent(ratio: Float): String = "%.1f%%".format(ratio * 100)

/** 结余要带符号：不带的「1,000.00」看不出是结余还是缺口。 */
private fun signed(amountMinor: Long, currency: String): String =
    (if (amountMinor >= 0) "+" else "−") +
        Money.of(abs(amountMinor), currency).format()
