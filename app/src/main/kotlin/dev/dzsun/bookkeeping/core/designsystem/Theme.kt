package dev.dzsun.bookkeeping.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 蓝色主调：偏现代商务，对比度按 M3 规范取值
private val Blue40 = Color(0xFF2456D6)
private val Blue80 = Color(0xFFAEC6FF)
private val BlueContainerLight = Color(0xFFDBE1FF)
private val OnBlueContainerLight = Color(0xFF00174B)
private val BlueContainerDark = Color(0xFF0B3B8F)
private val OnBlueContainerDark = Color(0xFFDBE1FF)

private val Teal40 = Color(0xFF2B6B6B)
private val Teal80 = Color(0xFF8FD3D3)

private val LightScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = BlueContainerLight,
    onPrimaryContainer = OnBlueContainerLight,
    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBFE9E9),
    onSecondaryContainer = Color(0xFF002020),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1A1B20),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Color(0xFF002A77),
    primaryContainer = BlueContainerDark,
    onPrimaryContainer = OnBlueContainerDark,
    secondary = Teal80,
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF124F4F),
    onSecondaryContainer = Color(0xFFBFE9E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    error = Color(0xFFFFB4AB),
)

@Composable
fun BookkeepingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
