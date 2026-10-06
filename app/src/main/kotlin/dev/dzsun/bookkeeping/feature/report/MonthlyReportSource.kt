package dev.dzsun.bookkeeping.feature.report

import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * 某个月的原始聚合从哪来。
 *
 * **这是留给数据层的第二个对接点。** 那边正在做月度报表查询
 * （`getMonthlyReport(year, month)` 一类），接口就绪后在 `ReportModule` 换成它的适配器即可，
 * 报表页只认这个接口。现在先用 [LedgerMonthlyReportSource] 把真实数据接上，
 * 而不是拿假数据糊一个页面——**界面上摆一个假数字，比摆一个"还没接通"更糟**。
 */
interface MonthlyReportSource {
    fun observeMonth(yearMonth: YearMonth): Flow<MonthAggregate>
}

/**
 * 用现有的 SQL 聚合拼出月度报表。
 *
 * 四个查询的 `GROUP BY` / `SUM` 全在 SQL 里做（`PostingDao` 已排除 VOID 凭证），
 * **不把整本账目拉进内存再筛**：账本只会越长越大，而报表一次只看一个月。
 */
@Singleton
class LedgerMonthlyReportSource @Inject constructor(
    private val repository: LedgerRepository,
) : MonthlyReportSource {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMonth(yearMonth: YearMonth): Flow<MonthAggregate> {
        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()
        val previous = yearMonth.minusMonths(1)

        return repository.observeBaseCurrency().flatMapLatest { currency ->
            if (currency.isNullOrBlank()) {
                // 科目表还没建好，币种未知——连 Money 都构造不出来，先给一个"还算不出来"的聚合
                flowOf(MonthAggregate(yearMonth, null, 0L, 0L, emptyList(), 0L))
            } else {
                combine(
                    repository.observeTotal(AccountType.INCOME, currency, from, to),
                    repository.observeTotal(AccountType.EXPENSE, currency, from, to),
                    repository.observeCategoryTotals(AccountType.EXPENSE, from, to),
                    repository.observeTotal(
                        AccountType.EXPENSE,
                        currency,
                        previous.atDay(1),
                        previous.atEndOfMonth(),
                    ),
                ) { income, expense, categories, previousExpense ->
                    MonthAggregate(
                        yearMonth = yearMonth,
                        currency = currency,
                        // 收入侧分录在账上是负数（贷方），翻正交给界面——
                        // 与 LedgerViewModel 同一个口径，否则两处的"本月收入"一正一负
                        incomeMinor = -income,
                        expenseMinor = expense,
                        categories = categories,
                        previousExpenseMinor = previousExpense,
                    )
                }
            }
        }
    }
}
