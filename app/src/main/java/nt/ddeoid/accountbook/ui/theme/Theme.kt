package nt.ddeoid.accountbook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 用户的明暗模式偏好。`SYSTEM` 跟随系统,其余三套为手动固定。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

data class ThemePreferences(
    val mode: ThemeMode = ThemeMode.SYSTEM,
) {
    companion object {
        val Default = ThemePreferences()
    }
}

val LocalThemePreferences = staticCompositionLocalOf { ThemePreferences.Default }

/**
 * 应用主题根。`preference` 决定明暗/AMOLED,Composable 内部会根据系统设置二次决策。
 */
@Composable
fun AccountBookTheme(
    preference: ThemePreferences = ThemePreferences.Default,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (preference.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val isAmoled = preference.mode == ThemeMode.AMOLED

    val colorScheme = when {
        isAmoled -> darkColorScheme(
            primary = AmoledPrimary,
            onPrimary = AmoledOnPrimary,
            primaryContainer = AmoledPrimaryContainer,
            onPrimaryContainer = AmoledOnPrimaryContainer,
            secondary = AmoledSecondary,
            onSecondary = AmoledOnSecondary,
            secondaryContainer = AmoledSecondaryContainer,
            onSecondaryContainer = AmoledOnSecondaryContainer,
            tertiary = AmoledTertiary,
            background = AmoledBackground,
            onBackground = AmoledOnBackground,
            surface = AmoledSurface,
            onSurface = AmoledOnSurface,
            surfaceVariant = AmoledSurfaceVariant,
            onSurfaceVariant = AmoledOnSurfaceVariant,
            outline = AmoledOutline,
            error = AmoledError,
            onError = AmoledOnError,
        )
        useDark -> darkColorScheme(
            primary = DarkPrimary,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkPrimaryContainer,
            onPrimaryContainer = DarkOnPrimaryContainer,
            secondary = DarkSecondary,
            onSecondary = DarkOnSecondary,
            secondaryContainer = DarkSecondaryContainer,
            onSecondaryContainer = DarkOnSecondaryContainer,
            tertiary = DarkTertiary,
            background = DarkBackground,
            onBackground = DarkOnBackground,
            surface = DarkSurface,
            onSurface = DarkOnSurface,
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = DarkOnSurfaceVariant,
            outline = DarkOutline,
        )
        else -> lightColorScheme(
            primary = LightPrimary,
            onPrimary = LightOnPrimary,
            primaryContainer = LightPrimaryContainer,
            onPrimaryContainer = LightOnPrimaryContainer,
            secondary = LightSecondary,
            onSecondary = LightOnSecondary,
            secondaryContainer = LightSecondaryContainer,
            onSecondaryContainer = LightOnSecondaryContainer,
            tertiary = LightTertiary,
            background = LightBackground,
            onBackground = LightOnBackground,
            surface = LightSurface,
            onSurface = LightOnSurface,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = LightOnSurfaceVariant,
            outline = LightOutline,
        )
    }

    CompositionLocalProvider(LocalThemePreferences provides preference) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AccountBookTypography,
            content = content,
        )
    }
}