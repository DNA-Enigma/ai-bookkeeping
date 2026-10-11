package dev.dzsun.bookkeeping.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** 验证码（6 位数字）。 */
    val code: String = "",
    /** 验证码登录 / 密码登录 二选一。默认验证码——密码可选是产品口径。 */
    val useCodeLogin: Boolean = true,
    /** 发送按钮倒计时（秒）。0 = 可发送。数字以服务端 resend_after 为准。 */
    val resendCountdown: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val canSubmitLogin: Boolean
        get() = if (useCodeLogin) {
            email.isNotBlank() && code.length == 6 && !isLoading
        } else {
            email.isNotBlank() && password.isNotBlank() && !isLoading
        }
    val canSubmitRegister: Boolean
        get() = email.isNotBlank() && code.length == 6 && !isLoading
    val canSendCode: Boolean get() = email.isNotBlank() && resendCountdown == 0 && !isLoading
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            // 启动流：短暂品牌动画，然后按会话分流（JWT 有效直接进主页）
            _state.update {
                it.copy(route = if (repository.isLoggedIn()) AuthRoute.MAIN else AuthRoute.LOGIN)
            }
        }
    }

    fun onEmailChange(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onConfirmPasswordChange(v: String) = _state.update { it.copy(confirmPassword = v, error = null) }
    fun onResetEmailChange(v: String) = _state.update { it.copy(resetEmail = v, error = null, notice = null) }
    fun onCodeChange(v: String) = _state.update { it.copy(code = v.filter { c -> c.isDigit() }.take(6), error = null) }
    fun toggleLoginMode() = _state.update { it.copy(useCodeLogin = !it.useCodeLogin, error = null) }

    fun goLogin() = _state.update { it.copy(route = AuthRoute.LOGIN, error = null, notice = null) }
    fun goRegister() = _state.update { it.copy(route = AuthRoute.REGISTER, error = null, notice = null) }
    fun goForgot() = _state.update { it.copy(route = AuthRoute.FORGOT, error = null, notice = null) }

    /** 发验证码。倒计时以服务端返回的 resend_after 为准（不写死 60）。 */
    fun sendCode() {
        val s = _state.value
        if (!s.canSendCode) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val outcome = repository.sendCode(s.email.trim())) {
                is SendCodeOutcome.Sent -> {
                    _state.update { it.copy(isLoading = false, notice = "验证码已发送，5 分钟内有效") }
                    startCountdown(outcome.resendAfterSeconds)
                }
                is SendCodeOutcome.Failed -> _state.update {
                    it.copy(isLoading = false, error = outcome.message)
                }
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var left = if (seconds > 0) seconds else 60
            _state.update { it.copy(resendCountdown = left) }
            while (left > 0) {
                delay(1000)
                left -= 1
                _state.update { it.copy(resendCountdown = left) }
            }
        }
    }

    fun signIn() {
        val s = _state.value
        if (!s.canSubmitLogin) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = if (s.useCodeLogin) {
                repository.signInWithCode(s.email.trim(), s.code)
            } else {
                repository.signIn(s.email.trim(), s.password)
            }
            when (result) {
                is AuthResult.Success -> _state.update {
                    it.copy(isLoading = false, route = AuthRoute.MAIN, password = "", code = "")
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
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.register(s.email.trim(), s.code, s.password.ifBlank { null })) {
                is AuthResult.Success -> _state.update {
                    it.copy(isLoading = false, route = AuthRoute.MAIN, password = "", confirmPassword = "", code = "")
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
            // 重置 = 发验证码（同一个 send-code 端点），旧的"发重置说明"文案
            // 是本地版的遗留语义，服务端版就是发验证码。
            when (val outcome = repository.sendCode(email)) {
                is SendCodeOutcome.Sent -> _state.update {
                    it.copy(isLoading = false, notice = "验证码已发送，5 分钟内有效")
                }
                is SendCodeOutcome.Failed -> _state.update {
                    it.copy(isLoading = false, error = outcome.message)
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOut()
            _state.update {
                it.copy(route = AuthRoute.LOGIN, email = "", password = "", confirmPassword = "", code = "")
            }
        }
    }
}
