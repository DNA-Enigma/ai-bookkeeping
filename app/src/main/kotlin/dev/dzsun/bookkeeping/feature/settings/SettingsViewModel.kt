package dev.dzsun.bookkeeping.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val currency: String = "CNY",
    val expenseCategories: List<AccountEntity> = emptyList(),
    val incomeCategories: List<AccountEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: LedgerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

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
}
