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
import dev.dzsun.bookkeeping.core.statement.MerchantCategorization
import dev.dzsun.bookkeeping.core.statement.MerchantCategorizer
import dev.dzsun.bookkeeping.core.statement.MerchantSuggestion
import dev.dzsun.bookkeeping.core.statement.StatementImporter
import dev.dzsun.bookkeeping.core.statement.UnknownMerchant
import dev.dzsun.bookkeeping.core.statement.resolveCategories
import dev.dzsun.bookkeeping.core.statement.unknownMerchants
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
    /** 逐条改分类时可选的收支分类。分类是数据，从科目表读，不在代码里写死。 */
    val categories: List<AccountEntity> = emptyList(),

    // ---- 陌生商户批量归类（导入完成之后） ----

    /** 这次导入里落到兜底分类、用户从没归过类的商户。没有就不提这件事。 */
    val unknownMerchants: List<UnknownMerchant> = emptyList(),

    /** 调度层给的归类建议。null 表示还没问过。 */
    val suggestions: List<MerchantSuggestion>? = null,

    /** 归类请求在跑（问调度层，或正在落库）。 */
    val classifying: Boolean = false,

    /** 拿不到建议的原因。**不是错误**——用户改不成，说了也只是告诉他为什么。 */
    val classifyNote: String? = null,

    /** 采纳之后真正改到的分录条数。null 表示还没采纳过。 */
    val appliedCount: Int? = null,

    /** 采纳过程中写失败的原因。 */
    val applyError: String? = null,

    /** 用户选择「不用了」。 */
    val classifyDismissed: Boolean = false,
) {
    val selectedAccountName: String?
        get() = accounts.find { it.id == selectedAccountId }?.name

    /** 选了文件也选了账户才敢去解析——账户是流水里没有的信息，缺了落不了账。 */
    val canPrepare: Boolean get() = fileName != null && selectedAccountId != null && !isBusy

    /** 该不该提「发现 N 个陌生商户」。 */
    val shouldOfferClassification: Boolean
        get() = unknownMerchants.isNotEmpty() && suggestions == null &&
            appliedCount == null && !classifyDismissed

    /** 该不该显示建议列表。 */
    val showingSuggestions: Boolean get() = suggestions != null && appliedCount == null

    /** 建议里还有没选好分类的，采纳按钮就得禁用——落不了库的东西不该能点。 */
    val canApplySuggestions: Boolean
        get() = suggestions?.isNotEmpty() == true && suggestions.all { it.resolved } && !classifying
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importer: StatementImporter,
    private val repository: LedgerRepository,
    private val categorizer: MerchantCategorizer,
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
                    categories = expense + income,
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
        // 陌生商户要在**丢掉 plan 之前**算出来：落库之后只剩凭证 id，商户名
        // 得回头去查库，而那时已经分不清哪些是「这次新出现的」
        val unknown = stage.plan.unknownMerchants()
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val outcome = importer.commit(stage.plan.entries)
            _state.update {
                it.copy(
                    isBusy = false,
                    stage = ImportStage.Committed(outcome),
                    unknownMerchants = unknown,
                )
            }
        }
    }

    // ------------------------------------------------ 陌生商户批量归类

    /**
     * 把陌生商户**一次**交给调度层归类（约定 7）。
     *
     * 分类名从本机科目表读出来一起发过去——「分类是数据不是代码」，
     * 调度层不认识用户自己的体系，只能从传进去的集合里选。
     */
    fun onClassifyMerchants() {
        val merchants = _state.value.unknownMerchants
        if (merchants.isEmpty() || _state.value.classifying) return
        _state.update { it.copy(classifying = true, classifyNote = null, classifyDismissed = false) }
        viewModelScope.launch {
            when (val outcome = categorizer.categorize(merchants, _state.value.categories.map { it.name })) {
                is MerchantCategorization.Suggested -> _state.update {
                    it.copy(
                        classifying = false,
                        // 建议给的是**分类名**，对到本机账户 id 这一步只能在本地做
                        suggestions = outcome.suggestions.resolveCategories(it.categories),
                    )
                }

                is MerchantCategorization.Unavailable -> _state.update {
                    it.copy(classifying = false, classifyNote = outcome.reason)
                }
            }
        }
    }

    /** 用户逐条改了某个商户的分类。 */
    fun onSuggestionChanged(payee: String, categoryId: String) {
        _state.update { state ->
            state.copy(
                suggestions = state.suggestions?.map { suggestion ->
                    if (suggestion.payee == payee) suggestion.copy(categoryId = categoryId) else suggestion
                },
            )
        }
    }

    /** 用户选择不用智能归类，自己手工处理。 */
    fun onDismissClassification() {
        _state.update { it.copy(classifyDismissed = true) }
    }

    /**
     * 把确认过的归类落库。
     *
     * 改的是**这次导入进去的那些凭证**的分类分录——顺带也就「学了一遍」：
     * `categoryForMerchant` 读的是历史分录，下次导入同一个商户会被认出来。
     */
    fun onApplySuggestions() {
        val stage = _state.value.stage as? ImportStage.Committed ?: return
        val suggestions = _state.value.suggestions ?: return
        if (!_state.value.canApplySuggestions) return

        _state.update { it.copy(classifying = true, applyError = null) }
        viewModelScope.launch {
            val journalIdsByPayee = stage.outcome.posted
                .groupBy { it.entry.row.merchant?.trim().orEmpty() }
                .mapValues { (_, posted) -> posted.map { it.journalId } }

            var changed = 0
            val failures = mutableListOf<String>()
            for (suggestion in suggestions) {
                val categoryId = suggestion.categoryId ?: continue
                val ids = journalIdsByPayee[suggestion.payee].orEmpty()
                runCatching { repository.recategorize(ids, categoryId) }
                    .onSuccess { changed += it }
                    .onFailure { failures += "${suggestion.payee}：${it.message}" }
            }
            _state.update {
                it.copy(
                    classifying = false,
                    appliedCount = changed,
                    applyError = failures.firstOrNull(),
                )
            }
        }
    }

    /** 落完或放弃后再来一份。归类的状态一并清掉——那是上一份文件的事。 */
    fun restart() {
        fileBytes = null
        _state.update {
            ImportUiState(
                accounts = it.accounts,
                selectedAccountId = it.selectedAccountId,
                currency = it.currency,
                categories = it.categories,
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
