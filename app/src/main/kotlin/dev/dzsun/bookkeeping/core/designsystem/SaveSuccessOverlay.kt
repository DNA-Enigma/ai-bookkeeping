package dev.dzsun.bookkeeping.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 记账成功反馈：对勾圆标弹入 + 金额上浮淡出。
 * 动画播完回调 [onFinished]，由调用方负责关面板。
 */
@Composable
fun SaveSuccessOverlay(
    amountLabel: String,
    onFinished: () -> Unit,
) {
    val iconScale = remember { Animatable(0.3f) }
    val textAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        iconScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
        textAlpha.animateTo(1f, animationSpec = tween(280))
        delay(700)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(iconScale.value)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(IncomeGreen, BrandBlue))),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "已记一笔",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha.value),
            )
            if (amountLabel.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    amountLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = BrandBlue.copy(alpha = textAlpha.value),
                )
            }
        }
    }
}
