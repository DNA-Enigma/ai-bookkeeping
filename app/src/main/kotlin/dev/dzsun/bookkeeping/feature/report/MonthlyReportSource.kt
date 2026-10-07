package dev.dzsun.bookkeeping.feature.report

import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.ledger.MonthAggregate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 某个月的原始聚合从哪来。
 *
 * 报表页只认这个接口，不直接依赖 [LedgerRepository]——换数据来源时只动 `ReportModule`
 * 里的一个绑定，报表页、预算页都不受影响。
 */
interface MonthlyReportSource {
    fun observeMonth(yearMonth: YearMonth): Flow<MonthAggregate>
}

/**
 * 数据层的月度报表查询的适配器。
 *
 * 聚合本身（日期窗口、四个 SQL、收入翻正）现在**在 `LedgerRepository.observeMonthlyReport` 里**，
 * 这里只是把接口接上——界面侧不再自己拼 `atDay(1)`/`atEndOfMonth()`、
 * 也不再自己 combine 四个查询。留这一层是为了保住 [MonthlyReportSource] 这个缝。
 */
@Singleton
class LedgerMonthlyReportSource @Inject constructor(
    private val repository: LedgerRepository,
) : MonthlyReportSource {

    override fun observeMonth(yearMonth: YearMonth): Flow<MonthAggregate> =
        repository.observeMonthlyReport(yearMonth)
}
