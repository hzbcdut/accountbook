package nt.ddeoid.accountbook.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 排版预设。Phase 1 沿用 M3 默认值,后续可以替换中文更优字体。
 */
internal val AccountBookTypography: Typography = Typography().run {
    val baseBody = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    )
    copy(
        bodyLarge = baseBody,
        bodyMedium = baseBody.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = baseBody.copy(fontSize = 12.sp, lineHeight = 16.sp),
        titleLarge = baseBody.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = baseBody.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        titleSmall = baseBody.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelLarge = baseBody.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = baseBody.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = baseBody.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )
}