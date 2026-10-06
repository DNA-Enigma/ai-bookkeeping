package dev.dzsun.bookkeeping.feature.auth

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dzsun.bookkeeping.core.designsystem.BrandBlue
import dev.dzsun.bookkeeping.core.designsystem.IncomeGreen

/** 启动页：品牌圆标淡入上浮，然后由 ViewModel 分流到登录或主界面。 */
@Composable
fun SplashScreen() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(600),
        label = "splashAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(BrandBlue, IncomeGreen))),
                contentAlignment = Alignment.Center,
            ) {
                Text("记", fontSize = 40.sp, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(16.dp))
            Text("AI 记账", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "说一句、拍一张，账就记好了",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LoginScreen(state: AuthUiState, viewModel: AuthViewModel) {
    AuthScaffold(
        title = "欢迎回来",
        subtitle = "登录后账单在多台设备间同步",
    ) {
        EmailPasswordFields(state, viewModel)
        Button(
            onClick = viewModel::signIn,
            enabled = state.canSubmitLogin,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("登录", fontWeight = FontWeight.SemiBold)
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = viewModel::goForgot) {
            Text("忘记密码？")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            Text("还没有账号？", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = viewModel::goRegister) { Text("去注册") }
        }
    }
}

@Composable
fun RegisterScreen(state: AuthUiState, viewModel: AuthViewModel) {
    AuthScaffold(
        title = "创建账号",
        subtitle = "邮箱 + 密码，一分钟搞定",
    ) {
        EmailPasswordFields(state, viewModel, showConfirm = true)
        Button(
            onClick = viewModel::register,
            enabled = state.canSubmitRegister,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("注册并进入", fontWeight = FontWeight.SemiBold)
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.Center) {
            Text("已有账号？", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = viewModel::goLogin) { Text("去登录") }
        }
    }
}

@Composable
fun ForgotPasswordScreen(state: AuthUiState, viewModel: AuthViewModel) {
    AuthScaffold(
        title = "重置密码",
        subtitle = "输入注册邮箱，我们发重置说明",
    ) {
        OutlinedTextField(
            value = state.resetEmail,
            onValueChange = viewModel::onResetEmailChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("邮箱") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            shape = RoundedCornerShape(12.dp),
        )
        Button(
            onClick = viewModel::sendReset,
            enabled = state.resetEmail.isNotBlank() && !state.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("发送重置说明", fontWeight = FontWeight.SemiBold) }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.notice?.let {
            Text(it, color = IncomeGreen, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = viewModel::goLogin) { Text("返回登录") }
    }
}

@Composable
private fun AuthScaffold(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        content()
    }
}

@Composable
private fun EmailPasswordFields(
    state: AuthUiState,
    viewModel: AuthViewModel,
    showConfirm: Boolean = false,
) {
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.email,
        onValueChange = viewModel::onEmailChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("邮箱") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        shape = RoundedCornerShape(12.dp),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("密码") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(12.dp),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                )
            }
        },
    )
    if (showConfirm) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.confirmPassword,
            onValueChange = viewModel::onConfirmPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("确认密码") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(12.dp),
            isError = state.confirmPassword.isNotBlank() && state.confirmPassword != state.password,
            supportingText = {
                if (state.confirmPassword.isNotBlank() && state.confirmPassword != state.password) {
                    Text("两次密码不一致")
                }
            },
        )
    }
    Spacer(Modifier.height(20.dp))
}
