package dev.dzsun.bookkeeping

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.dzsun.bookkeeping.core.designsystem.BookkeepingTheme
import dev.dzsun.bookkeeping.feature.auth.AuthRoute
import dev.dzsun.bookkeeping.feature.auth.AuthViewModel
import dev.dzsun.bookkeeping.feature.auth.ForgotPasswordScreen
import dev.dzsun.bookkeeping.feature.auth.LoginScreen
import dev.dzsun.bookkeeping.feature.auth.RegisterScreen
import dev.dzsun.bookkeeping.feature.auth.SplashScreen
import dev.dzsun.bookkeeping.navigation.AppNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BookkeepingTheme {
                RootFlow()
            }
        }
    }
}

/** 启动流门禁：Splash → 登录/注册/忘记密码 → 主界面。 */
@Composable
private fun RootFlow(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (state.route) {
        AuthRoute.SPLASH -> SplashScreen()
        AuthRoute.LOGIN -> LoginScreen(state, viewModel)
        AuthRoute.REGISTER -> RegisterScreen(state, viewModel)
        AuthRoute.FORGOT -> ForgotPasswordScreen(state, viewModel)
        AuthRoute.MAIN -> AppNavHost(onSignOut = viewModel::signOut)
    }
}
