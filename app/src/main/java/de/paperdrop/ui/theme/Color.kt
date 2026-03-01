package de.paperdrop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Light
val md_theme_light_primary           = Color(0xFF1A6B3C)
val md_theme_light_onPrimary         = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer  = Color(0xFFB7F1C8)
val md_theme_light_secondary         = Color(0xFF4E6356)
val md_theme_light_background        = Color(0xFFF8FAF8)
val md_theme_light_surface           = Color(0xFFF8FAF8)
val md_theme_light_error             = Color(0xFFBA1A1A)

// Dark
val md_theme_dark_primary            = Color(0xFF6BDC90)
val md_theme_dark_onPrimary          = Color(0xFF00391C)
val md_theme_dark_primaryContainer   = Color(0xFF00522B)
val md_theme_dark_secondary          = Color(0xFFB3CCBA)
val md_theme_dark_background         = Color(0xFF191C19)
val md_theme_dark_surface            = Color(0xFF191C19)
val md_theme_dark_error              = Color(0xFFFFB4AB)

val Typography = Typography(
    bodyLarge   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    titleLarge  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,     fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelSmall  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,   fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)
