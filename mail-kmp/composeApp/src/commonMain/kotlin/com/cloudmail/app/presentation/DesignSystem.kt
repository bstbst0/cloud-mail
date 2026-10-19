package com.cloudmail.app.presentation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.cloudmail.app.platformColorScheme

object MailSpace {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val navigation = 220.dp
    val list = 380.dp
}

private val light = lightColorScheme(
    primary = Color(0xFF315DA8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF), onPrimaryContainer = Color(0xFF173665),
    secondary = Color(0xFF53637C), secondaryContainer = Color(0xFFE1E7F2),
    tertiary = Color(0xFF456B60), background = Color(0xFFF7F9FD),
    surface = Color(0xFFF7F9FD), surfaceVariant = Color(0xFFE8EDF5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFEBF0F7), surfaceContainerHigh = Color(0xFFE5EBF4), surfaceContainerHighest = Color(0xFFDFE7F2),
    onSurface = Color(0xFF1C2533), onSurfaceVariant = Color(0xFF566176),
    outline = Color(0xFF778397), outlineVariant = Color(0xFFD3DAE5),
)
private val dark = darkColorScheme(
    primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF143365),
    primaryContainer = Color(0xFF244B82), onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFBDC9DE), secondaryContainer = Color(0xFF35445C),
    tertiary = Color(0xFFA7CFC1), background = Color(0xFF10151D),
    surface = Color(0xFF151C27), surfaceVariant = Color(0xFF263141),
    surfaceContainerLowest = Color(0xFF0C1119), surfaceContainerLow = Color(0xFF19212D),
    surfaceContainer = Color(0xFF1E2735), surfaceContainerHigh = Color(0xFF253041), surfaceContainerHighest = Color(0xFF2D394D),
    onSurface = Color(0xFFE5EAF3), onSurfaceVariant = Color(0xFFB8C3D5),
    outline = Color(0xFF8290A5), outlineVariant = Color(0xFF354255),
)

@Composable
fun CloudMailTheme(theme: String, textScale: Float, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val base = Typography()
    val scale = textScale.coerceIn(.85f, 1.5f)
    fun scaled(style: TextStyle) = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
    val typography = base.copy(
        headlineLarge = scaled(base.headlineLarge), headlineMedium = scaled(base.headlineMedium),
        headlineSmall = scaled(base.headlineSmall), titleLarge = scaled(base.titleLarge),
        titleMedium = scaled(base.titleMedium), titleSmall = scaled(base.titleSmall),
        bodyLarge = scaled(base.bodyLarge), bodyMedium = scaled(base.bodyMedium),
        bodySmall = scaled(base.bodySmall), labelLarge = scaled(base.labelLarge),
        labelMedium = scaled(base.labelMedium), labelSmall = scaled(base.labelSmall),
    )
    val useDark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = platformColorScheme(useDark, dynamicColor) ?: if (useDark) dark else light,
        typography = typography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(22.dp)),
        content = content,
    )
}
