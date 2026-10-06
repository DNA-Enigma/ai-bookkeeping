package dev.dzsun.bookkeeping.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.BuildConfig
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.update.ApkInstaller
import dev.dzsun.bookkeeping.core.update.UpdateChecker
import dev.dzsun.bookkeeping.core.update.UpdateManifest
import dev.dzsun.bookkeeping.core.update.UpdateStatus
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val updateChecker: UpdateChecker,
    private val apkInstaller: ApkInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** 已下载好的 APK，授权回来后直接装，不重复下载。 */
    private var pendingApk: File? = null

    init {
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
    fun checkForUpdate() {
        if (_state.value.update is UpdateUiState.Checking) return
        _state.update { it.copy(update = UpdateUiState.Checking) }
        viewModelScope.launch {
            when (val status = updateChecker.check(state.value.versionCode, state.value.versionName)) {
                is UpdateStatus.UpToDate -> _state.update { it.copy(update = UpdateUiState.UpToDate) }
                is UpdateStatus.Available -> _state.update { it.copy(update = UpdateUiState.Available(status.manifest)) }
                is UpdateStatus.Failed -> _state.update { it.copy(update = UpdateUiState.Error(status.reason)) }
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
                    _state.update {
                        it.copy(update = UpdateUiState.Error(error.message ?: "下载失败"))
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
