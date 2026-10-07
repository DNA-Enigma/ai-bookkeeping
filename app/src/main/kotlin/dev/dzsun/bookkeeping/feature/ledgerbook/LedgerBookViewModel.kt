package dev.dzsun.bookkeeping.feature.ledgerbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountPeriodBalance
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.VoucherLine
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.platform.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 余额表的一行。金额一律整数最小单位（分），展示层再 `/100`。
 *
 * [openingMinor] / [closingMinor] 已按科目余额方向翻正（贷方科目的正数表示贷方余额），
 * 所以界面上可以直接排数字，不需要再判断方向。
 */
data class BalanceRowUi(
    val code: String,
    val name: String,
    val openingMinor: Long,
    val debitMinor: Long,
    val creditMinor: Long,
    val closingMinor: Long,
)

/** 凭证上的一条分录。[isDebit] 为真时是借方行，否则是贷方行（缩进显示）。 */
data class VoucherLineUi(
    val isDebit: Boolean,
    val accountLabel: String,
    val amountMinor: Long,
)

/** 一张凭证的展示态。[no] 是当月内的顺序编号（「记 · 第 N 号」），不是库里的 id。 */
data class VoucherUi(
    val no: String,
    val dateText: String,
    val lines: List<VoucherLineUi>,
    val memo: String,
)

data class LedgerBookUiState(
    /** 账期。与报表页同一口径：[Clock.today] 所在的月。 */
    val month: YearMonth,
    val assetRows: List<BalanceRowUi> = emptyList(),
    val liabilityRows: List<BalanceRowUi> = emptyList(),
    val expenseRows: List<BalanceRowUi> = emptyList(),
    val incomeRows: List<BalanceRowUi> = emptyList(),
    val equityRows: List<BalanceRowUi> = emptyList(),
    /** 本期借方合计（全部科目）。与 [totalCreditMinor] 在一本平账上必然相等。 */
    val totalDebitMinor: Long = 0,
    val totalCreditMinor: Long = 0,
    val vouchers: List<VoucherUi> = emptyList(),
    /** 该月（含期初）完全没有分录。空态不显示假科目、假凭证。 */
    val isEmpty: Boolean = true,
    /**
     * Room 首帧还没到。
     *
     * 这一位不能省：[isEmpty] 默认为真，首帧到达前页面会把加载中当成
     * 「本月还没有分录」——那是在告诉用户一个还没算出来的结论。
     * （与 `MonthlyReportQuery` 里「不算出来 ≠ 空」的约定同源。）
     */
    val isLoading: Boolean = true,
) {
    /** 借贷不平。真不平时差额会照实显示，不四舍五入掩盖。 */
    val isUnbalanced: Boolean get() = totalDebitMinor != totalCreditMinor
}

/**
 * 复式账簿页。数据来自 [LedgerRepository] 的按月 SQL 聚合
 * （科目余额试算 + 凭证明细），**不把整本账目拉进内存再筛**。
 */
@HiltViewModel
class LedgerBookViewModel @Inject constructor(
    private val repository: LedgerRepository,
    clock: Clock,
) : ViewModel() {

    /** 与报表页同源：[Clock.today] 所在的月，不自己 `YearMonth.now()`。 */
    private val month = YearMonth.from(clock.today())

    private val _state = MutableStateFlow(LedgerBookUiState(month = month))
    val state: StateFlow<LedgerBookUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeAccountBalances(month),
                repository.observeVoucherLines(month),
            ) { balances, lines ->
                assemble(month, balances, lines)
            }.collect { _state.update { it.copy(isLoading = false) } }
        }
    }

    private fun assemble(
        month: YearMonth,
        balances: List<AccountPeriodBalance>,
        lines: List<VoucherLine>,
    ): LedgerBookUiState {
        val grouped = balances.groupBy { it.accountType }
        fun rowsOf(type: AccountType): List<BalanceRowUi> =
            grouped[type].orEmpty().map { it.toRowUi() }

        val totalDebit = balances.sumOf { it.debitMinor }
        val totalCredit = balances.sumOf { it.creditMinor }
        val vouchers = assembleVouchers(lines)

        return LedgerBookUiState(
            month = month,
            assetRows = rowsOf(AccountType.ASSET),
            liabilityRows = rowsOf(AccountType.LIABILITY),
            expenseRows = rowsOf(AccountType.EXPENSE),
            incomeRows = rowsOf(AccountType.INCOME),
            equityRows = rowsOf(AccountType.EQUITY),
            totalDebitMinor = totalDebit,
            totalCreditMinor = totalCredit,
            vouchers = vouchers,
            isEmpty = balances.isEmpty() && vouchers.isEmpty(),
        )
    }

    private fun AccountPeriodBalance.toRowUi(): BalanceRowUi {
        val isDebitNormal = accountType == AccountType.ASSET || accountType == AccountType.EXPENSE
        // 损益类（收支）的「余额」按发生额口径展示：期初无余额概念（显示 0 → 界面「—」），
        // 期末就是本期净发生。与静态演示及月度报表一致。
        val isProfitLoss = accountType == AccountType.EXPENSE || accountType == AccountType.INCOME
        val opening = if (isProfitLoss) 0L else signed(openingNetMinor, isDebitNormal)
        val closing = if (isProfitLoss) {
            if (isDebitNormal) debitMinor - creditMinor else creditMinor - debitMinor
        } else {
            signed(openingNetMinor + debitMinor - creditMinor, isDebitNormal)
        }
        return BalanceRowUi(
            code = accountId,
            name = accountName,
            openingMinor = opening,
            debitMinor = debitMinor,
            creditMinor = creditMinor,
            closingMinor = closing,
        )
    }

    /** 把借方为正的净额翻成科目余额方向上的正数。 */
    private fun signed(netDebit: Long, isDebitNormal: Boolean): Long =
        if (isDebitNormal) netDebit else -netDebit
}

/** 把 SQL 返回的分录行按凭证分组，折成展示态。 */
private fun assembleVouchers(lines: List<VoucherLine>): List<VoucherUi> {
    if (lines.isEmpty()) return emptyList()
    // SQL 已按日期倒序、借方在前；同凭证多行相邻，顺序分组即可。
    val groups = LinkedHashMap<String, MutableList<VoucherLine>>()
    for (line in lines) {
        groups.getOrPut(line.journalId) { mutableListOf() }.add(line)
    }
    // 编号按时间正序（最早的凭证是第 1 号），展示时仍按倒序。
    val chronological = groups.entries.sortedBy { it.value.first().dateEpochDay }
    val numberByJournal = chronological.mapIndexed { index, entry -> entry.key to index + 1 }
        .toMap()

    return groups.entries.map { (journalId, postings) ->
        val head = postings.first()
        val date = LocalDate.ofEpochDay(head.dateEpochDay)
        VoucherUi(
            no = "记 · 第 %04d 号".format(numberByJournal[journalId]),
            dateText = "%d 月 %02d 日".format(date.monthValue, date.dayOfMonth),
            lines = postings.map { posting ->
                VoucherLineUi(
                    isDebit = posting.amountMinor > 0,
                    accountLabel = posting.accountName,
                    amountMinor = kotlin.math.abs(posting.amountMinor),
                )
            },
            memo = buildMemo(head),
        )
    }
}

private fun buildMemo(head: VoucherLine): String {
    val parts = listOfNotNull(head.note, head.payee, head.place).filter { it.isNotBlank() }
    return if (parts.isEmpty()) "摘要：—" else "摘要：" + parts.joinToString(" · ")
}
