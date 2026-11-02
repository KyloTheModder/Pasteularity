package win.libpaste.pasteularity.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PstuColors {
    val Background = Color(0xFF140B24)
    // Lands on exactly #22133A over Background, while letting the dot web show through panels.
    val Surface = Color(0xD924143E)
    val SurfaceRaised = Color(0xFF2E1C4C)
    val Outline = Color(0xFF443361)
    val Accent = Color(0xFF9B5CF6)
    val OnAccent = Color(0xFF140B24)
    val Text = Color(0xFFECE2FF)
    val TextMuted = Color(0xFFB3A5CF)
    val Danger = Color(0xFFF08A80)
}

object Spacing {
    val S = 8.dp
    val M = 16.dp
    val L = 24.dp
    val XL = 32.dp
}

val TouchTarget = 56.dp
val PstuShape = RoundedCornerShape(12.dp)

private val Sans = FontFamily.SansSerif
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp)

private val PstuTypography = Typography(
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

private val PstuColorScheme = darkColorScheme(
    primary = PstuColors.Accent,
    onPrimary = PstuColors.OnAccent,
    background = PstuColors.Background,
    onBackground = PstuColors.Text,
    surface = PstuColors.Surface,
    onSurface = PstuColors.Text,
    surfaceVariant = PstuColors.SurfaceRaised,
    onSurfaceVariant = PstuColors.TextMuted,
    surfaceContainerHigh = PstuColors.SurfaceRaised,
    surfaceContainerHighest = PstuColors.SurfaceRaised,
    outline = PstuColors.Outline,
    outlineVariant = PstuColors.Outline,
    error = PstuColors.Danger,
    onError = PstuColors.OnAccent,
)

@Composable
fun PasteularityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PstuColorScheme,
        typography = PstuTypography,
        shapes = Shapes(
            extraSmall = PstuShape,
            small = PstuShape,
            medium = PstuShape,
            large = PstuShape,
            extraLarge = PstuShape,
        ),
    ) {
        // MaterialTheme leaves this black outside a Surface, and most screens here draw directly.
        CompositionLocalProvider(LocalContentColor provides PstuColors.Text, content = content)
    }
}
