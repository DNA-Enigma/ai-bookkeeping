package dev.dzsun.bookkeeping.feature.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.money.Money
import dev.dzsun.bookkeeping.core.statement.ImportOutcome
import dev.dzsun.bookkeeping.core.statement.ImportPlan
import dev.dzsun.bookkeeping.core.statement.ImportPreparation
import dev.dzsun.bookkeeping.core.statement.ImportRequest
import dev.dzsun.bookkeeping.core.statement.StatementImporter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 流水导入走到哪一步了。四步：选文件 → 输密码 → 预览 → 确认。 */
sealed interface ImportStage {
    data object SelectFile : ImportStage

    /** 是加密账单但还没给密码——**这不是错误**，是正常分支。 */
    data class NeedPassword(val wrongPassword: Boolean = false) : ImportStage

    data class Preview(val plan: ImportPlan) : ImportStage

    data class Committed(val outcome: ImportOutcome) : ImportStage
}

data class ImportUiState(
    val stage: ImportStage = ImportStage.SelectFile,
    val isBusy: Boolean = false,
    /** 能终止当前步骤的错误（文件读不了、格式认不出）。密码错走 [ImportStage.NeedPassword]。 */
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val fileName: String? = null,
    val accounts: List<AccountEntity> = emptyList(),
    val selectedAccountId: String? = null,
    val currency: String = "CNY",
) {
    val selectedAccountName: String?
        get() = accounts.find { it.id == selectedAccountId }?.name

    /** 选了文件也选了账户才敢去解析——账户是流水里没有的信息，缺了落不了账。 */
    val canPrepare: Boolean get() = fileName != null && selectedAccountId != null && !isBusy
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importer: StatementImporter,
    private val repository: LedgerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** 选中的文件字节。不放进 UiState——ByteArray 的 equals 是引用比，放进去会搞坏状态更新。 */
    private var fileBytes: ByteArray? = null

    private var fallbackExpenseCategoryId: String = ""
    private var fallbackIncomeCategoryId: String = ""

    init {
        viewModelScope.launch {
            val accounts = repository.accountsOfTypes(IMPORT_ACCOUNT_TYPES)
            val expense = repository.accountsOfTypes(listOf(AccountType.EXPENSE))
            val income = repository.accountsOfTypes(listOf(AccountType.INCOME))
            fallbackExpenseCategoryId = expense.fallbackExpenseId().orEmpty()
            fallbackIncomeCategoryId = income.fallbackIncomeId().orEmpty()
            // 首个值即可，不需要持续收集——导入用的是快照不是活页
            val currency = repository.observeBaseCurrency().first()
            _state.update {
                it.copy(
                    accounts = accounts,
                    selectedAccountId = accounts.firstOrNull()?.id,
                    currency = currency?.ifBlank { "CNY" } ?: "CNY",
                )
            }
        }
    }

    fun onAccountSelected(accountId: String) {
        _state.update { it.copy(selectedAccountId = accountId) }
    }

    /** 用户选完文件。真正解析等他按「下一步」，免得账户还没选就跑一轮白工。 */
    fun onFilePicked(fileName: String, bytes: ByteArray) {
        fileBytes = bytes
        _state.update {
            it.copy(
                fileName = fileName,
                error = null,
                warnings = emptyList(),
                stage = ImportStage.SelectFile,
            )
        }
    }

    /** 文件本身读不动（权限、被删、超大）。停在选文件页并说清楚。 */
    fun onFileReadFailed(reason: String) {
        _state.update {
            it.copy(error = UNREADABLE_PREFIX + reason, isBusy = false)
        }
    }

    /** 选文件 → （密码）→ 预览。[password] 为 null 表示还没问过。 */
    fun prepare(password: String? = null) {
        val bytes = fileBytes ?: return
        val accountId = _state.value.selectedAccountId ?: return
        if (_state.value.isBusy) return

        _state.update {
            it.copy(
                isBusy = true,
                error = null,
                stage = if (password == null) ImportStage.SelectFile else ImportStage.NeedPassword(),
            )
        }
        viewModelScope.launch {
            val result = importer.prepare(
                ImportRequest(
                    bytes = bytes,
                    password = password,
                    accountId = accountId,
                    fallbackExpenseCategoryId = fallbackExpenseCategoryId,
                    fallbackIncomeCategoryId = fallbackIncomeCategoryId,
                ),
            )
            _state.update { s ->
                when (result) {
                    is ImportPreparation.Ready -> s.copy(
                        isBusy = false,
                        stage = ImportStage.Preview(result.plan),
                        warnings = result.plan.warnings,
                        error = null,
                    )

                    ImportPreparation.NeedsPassword -> s.copy(
                        isBusy = false,
                        stage = ImportStage.NeedPassword(),
                    )

                    is ImportPreparation.WrongPassword -> s.copy(
                        isBusy = false,
                        // 密码错不是文件的错，留在密码步骤让人重试
                        stage = ImportStage.NeedPassword(wrongPassword = true),
                    )

                    is ImportPreparation.Unreadable -> s.copy(
                        isBusy = false,
                        stage = ImportStage.SelectFile,
                        error = UNREADABLE_PREFIX + result.reason,
                        warnings = result.warnings,
                    )
                }
            }
        }
    }

    /** 密码框的提交。空密码也传下去——用户可能真的设了空密码。 */
    fun onPasswordSubmit(password: String) {
        prepare(password = password)
    }

    /** 确认后才落账。只写 [ImportPlan.entries]，重复项一概不动。 */
    fun confirmImport() {
        val stage = _state.value.stage as? ImportStage.Preview ?: return
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val outcome = importer.commit(stage.plan.entries)
            _state.update {
                it.copy(isBusy = false, stage = ImportStage.Committed(outcome))
            }
        }
    }

    /** 落完或放弃后再来一份。 */
    fun restart() {
        fileBytes = null
        _state.update {
            ImportUiState(
                accounts = it.accounts,
                selectedAccountId = it.selectedAccountId,
                currency = it.currency,
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** 预览汇总用当前本位币。一份账单只有一种货币，按本位币展示即可。 */
    fun summaryOf(plan: ImportPlan): ImportSummary = plan.toSummary(_state.value.currency)

    fun formatAmount(minor: Long): String = Money.of(minor, _state.value.currency).format()
}
