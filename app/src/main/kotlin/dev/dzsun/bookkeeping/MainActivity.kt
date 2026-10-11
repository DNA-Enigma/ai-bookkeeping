package dev.dzsun.bookkeeping

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.dzsun.bookkeeping.llm.NotificationGate
import dev.dzsun.bookkeeping.navigation.AppNavHost
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var notificationGate: NotificationGate

    /**
     * 通知权限请求的 launcher。**在 onCreate 里注册、挂在 Activity 字段上**，
     * 不进 Compose 树——这样即使发起请求的页面已经不在（用户切走），
     * 系统对话框的回调也有地方落，不会因为 Composable 销毁而丢。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationGate.onPermissionResult(granted)
            // 拒绝不是终点：功能降级为页面内进度（进度组件本来就在），
            // 这里只记录状态，不弹二次对话框、不引导去系统设置——那属于打扰。
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // launcher 的停靠点交给门（单例）；进 AI 相关流程时由界面侧调 maybeRequest()。
        notificationGate.permissionLauncher = { unit: Unit ->
            unit // 占位消费：launcher 入参是 Unit，无负载
            requestNotificationPermissionIfNeeded()
        }
        setContent {
            BookkeepingTheme {
                RootFlow()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationGate.onPermissionResult(true)
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
