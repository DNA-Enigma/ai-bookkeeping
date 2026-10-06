package dev.dzsun.bookkeeping.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthRoute { SPLASH, LOGIN, REGISTER, FORGOT, MAIN }

data class AuthUiState(
    val route: AuthRoute = AuthRoute.SPLASH,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val resetEmail: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val canSubmitLogin: Boolean get() = email.isNotBlank() && password.isNotBlank() && !isLoading
    val canSubmitRegister: Boolean
        get() = email.isNotBlank() && password.length >= 6 && confirmPassword == password && !isLoading
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 启动流：短暂品牌动画，然后按会话分流
            _state.update {
                it.copy(route = if (repository.isLoggedIn()) AuthRoute.MAIN else AuthRoute.LOGIN)
            }
        }
    }

    fun onEmailChange(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onConfirmPasswordChange(v: String) = _state.update { it.copy(confirmPassword = v, error = null) }
    fun onResetEmailChange(v: String) = _state.update { it.copy(resetEmail = v, error = null, notice = null) }

    fun goLogin() = _state.update { it.copy(route = AuthRoute.LOGIN, error = null, notice = null) }
    fun goRegister() = _state.update { it.copy(route = AuthRoute.REGISTER, error = null, notice = null) }
    fun goForgot() = _state.update { it.copy(route = AuthRoute.FORGOT, error = null, notice = null) }

    fun signIn() {
        val s = _state.value
        if (!s.canSubmitLogin) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.signIn(s.email.trim(), s.password)) {
                is AuthResult.Success -> _state.update {
                    it.copy(isLoading = false, route = AuthRoute.MAIN, password = "")
                }

                is AuthResult.Failed -> _state.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun register() {
        val s = _state.value
        if (!s.canSubmitRegister) return
        if (s.password != s.confirmPassword) {
            _state.update { it.copy(error = "两次输入的密码不一致") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.register(s.email.trim(), s.password)) {
                is AuthResult.Success -> _state.update {
                    it.copy(isLoading = false, route = AuthRoute.MAIN, password = "", confirmPassword = "")
                }

                is AuthResult.Failed -> _state.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun sendReset() {
        val s = _state.value
        val email = s.resetEmail.trim()
        if (email.isBlank()) return
        _state.update { it.copy(isLoading = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val result = repository.sendResetCode(email)) {
                is AuthResult.Success -> _state.update {
                    it.copy(isLoading = false, notice = "重置说明已发到 $email（演示环境不真发信）")
                }

                is AuthResult.Failed -> _state.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOut()
            _state.update {
                it.copy(route = AuthRoute.LOGIN, email = "", password = "", confirmPassword = "")
            }
        }
    }
}
