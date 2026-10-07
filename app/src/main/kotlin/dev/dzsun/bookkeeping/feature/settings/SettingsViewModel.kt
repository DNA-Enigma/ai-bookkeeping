package dev.dzsun.bookkeeping.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.BuildConfig
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.ledger.ConfidenceGate
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.update.ApkInstaller
import dev.dzsun.bookkeeping.core.update.UpdateChecker
import dev.dzsun.bookkeeping.core.update.UpdateManifest
import dev.dzsun.bookkeeping.core.update.UpdateStatus
import dev.dzsun.bookkeeping.core.network.UserFacingErrors
import dev.dzsun.bookkeeping.feature.entry.AutoConfirmSettings
import dev.dzsun.bookkeeping.feature.ledger.MonthlyBudgetStore
import dev.dzsun.bookkeeping.feature.ledger.yuanToMinor
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 更新入口的界面状态。手动检查失败要给反馈——后台静默是另一回事。 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val manifest: UpdateManifest) : UpdateUiState
    data class Downloading(val manifest: UpdateManifest, val fraction: Float?) : UpdateUiState
    data class ReadyToInstall(val manifest: UpdateManifest) : UpdateUiState
    data class NeedInstallPermission(val manifest: UpdateManifest) : UpdateUiState
    data class Error(val reason: String) : UpdateUiState
}

data class SettingsUiState(
    val currency: String = "CNY",
    val expenseCategories: List<AccountEntity> = emptyList(),
    val incomeCategories: List<AccountEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val isLoading: Boolean = true,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val update: UpdateUiState = UpdateUiState.Idle,
    /** 自动入账阈值。数据源是 [AutoConfirmSettings]，设置页只读写它。 */
    val autoConfirmThreshold: Float = ConfidenceGate.DEFAULT_THRESHOLD,
    /** 可调范围，来自数据层的 assets 配置。 */
    val autoConfirmRange: ClosedFloatingPointRange<Float> =
        ConfidenceGate.MIN_THRESHOLD..ConfidenceGate.MAX_THRESHOLD,
    /** 月预算（最小单位）。0 = 不设。真源是 [MonthlyBudgetStore]。 */
    val budgetMinor: Long = 0L,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val updateChecker: UpdateChecker,
    private val apkInstaller: ApkInstaller,
    private val autoConfirmSettings: AutoConfirmSettings,
    private val monthlyBudgetStore: MonthlyBudgetStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** 已下载好的 APK，授权回来后直接装，不重复下载。 */
    private var pendingApk: File? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(autoConfirmRange = autoConfirmSettings.range) }
        }
        viewModelScope.launch {
            autoConfirmSettings.threshold.collect { value ->
                _state.update { it.copy(autoConfirmThreshold = value) }
            }
        }
        viewModelScope.launch {
            monthlyBudgetStore.budgetMinor.collect { value ->
                _state.update { it.copy(budgetMinor = value) }
            }
        }
        viewModelScope.launch {
            val currency = repository.observeBaseCurrency()
            // 首次拉取即可；分类管理的增删改属于后续接口，这里先做只读展示
            currency.collect { c ->
                val expense = repository.accountsOfTypes(listOf(AccountType.EXPENSE))
                val income = repository.accountsOfTypes(listOf(AccountType.INCOME))
                val accounts = repository.accountsOfTypes(listOf(AccountType.ASSET, AccountType.LIABILITY))
                _state.update {
                    it.copy(
                        currency = c.orEmpty().ifBlank { "CNY" },
                        expenseCategories = expense,
                        incomeCategories = income,
                        accounts = accounts,
                        isLoading = false,
                    )
                }
            }
        }
    }

    /** 设置页手动检查。失败在这里可以提示——用户正等着这个结果。 */
    /**
     * 改自动入账阈值。**立即生效**：写入的是 [AutoConfirmSettings] 这个单例，
     * 记账页读的是同一个源，所以不需要重启或重新进入页面。
     */
    fun onAutoConfirmThresholdChange(value: Float) = autoConfirmSettings.setThreshold(value)

    /**
     * 改月预算。**立即生效**：写的是 [MonthlyBudgetStore] 这个单例，
     * 首页读的是同一个源。认不出的输入不写、不清，由界面自己留着让用户改。
     */
    fun onBudgetYuanChange(text: String) {
        val minor = yuanToMinor(text) ?: return
        monthlyBudgetStore.setBudgetMinor(minor)
    }

    fun checkForUpdate() {
        if (_state.value.update is UpdateUiState.Checking) return
        _state.update { it.copy(update = UpdateUiState.Checking) }
        viewModelScope.launch {
            when (val status = updateChecker.check(state.value.versionCode, state.value.versionName)) {
                is UpdateStatus.UpToDate -> _state.update { it.copy(update = UpdateUiState.UpToDate) }
                is UpdateStatus.Available -> _state.update { it.copy(update = UpdateUiState.Available(status.manifest)) }
                is UpdateStatus.Failed -> _state.update {
                    // status.reason 可能带主机名/URL（见 UserFacingErrors），不透传。
                    it.copy(update = UpdateUiState.Error(UserFacingErrors.UPDATE))
                }
            }
        }
    }

    fun dismissUpdate() {
        pendingApk = null
        _state.update { it.copy(update = UpdateUiState.Idle) }
    }

    /** 下载 → 校验 → 装。缺「安装未知应用」授权时停在 [UpdateUiState.NeedInstallPermission]。 */
    fun downloadAndInstall(manifest: UpdateManifest) {
        _state.update { it.copy(update = UpdateUiState.Downloading(manifest, null)) }
        viewModelScope.launch {
            runCatching {
                apkInstaller.download(manifest) { done, total ->
                    val fraction = if (total > 0) done.toFloat() / total else null
                    _state.update {
                        if (it.update is UpdateUiState.Downloading) {
                            it.copy(update = UpdateUiState.Downloading(manifest, fraction))
                        } else it
                    }
                }
            }.fold(
                onSuccess = { apk ->
                    pendingApk = apk
                    if (apkInstaller.canRequestInstall()) {
                        withContext(Dispatchers.Main) { apkInstaller.install(apk) }
                        _state.update { it.copy(update = UpdateUiState.ReadyToInstall(manifest)) }
                    } else {
                        _state.update { it.copy(update = UpdateUiState.NeedInstallPermission(manifest)) }
                    }
                },
                onFailure = { error ->
                    // error.message 可能带 URL/主机名，界面只给白名单文案。
                    _state.update {
                        it.copy(update = UpdateUiState.Error(UserFacingErrors.UPDATE))
                    }
                },
            )
        }
    }

    /** 从「安装未知应用」授权页回来后调用。 */
    fun onInstallPermissionGranted() {
        val apk = pendingApk ?: return
        val manifest = (_state.value.update as? UpdateUiState.NeedInstallPermission)?.manifest ?: return
        if (!apkInstaller.canRequestInstall()) return
        viewModelScope.launch {
            withContext(Dispatchers.Main) { apkInstaller.install(apk) }
            _state.update { it.copy(update = UpdateUiState.ReadyToInstall(manifest)) }
        }
    }
}
