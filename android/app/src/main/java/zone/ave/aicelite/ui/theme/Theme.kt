package zone.ave.aicelite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import zone.ave.aicelite.protocol.Mode

/** The device lives on a wrist-flick glance, so: near-black, one accent, big numerals. */
object Ink {
    val Background = Color(0xFF08090C)
    val Surface = Color(0xFF14171C)
    val SurfaceElevated = Color(0xFF1B1F26)
    val Outline = Color(0x14FFFFFF)
    val Primary = Color(0xFFF3F5F8)
    val Secondary = Color(0xFF98A2B3)
    val Tertiary = Color(0xFF5B6472)
    val Danger = Color(0xFFFF6B5E)
}

/** The accent tracks the mode, so the screen reads cold or warm at a glance. */
val Mode?.accent: Color
    get() = when (this) {
        Mode.COOLING -> Color(0xFF4EA8FF)
        Mode.HEATING -> Color(0xFFFF8A3D)
        Mode.FAN -> Color(0xFF35D6C0)
        Mode.AI -> Color(0xFFB085FF)
        null -> Ink.Tertiary
    }

private val AiceColors = darkColorScheme(
    primary = Color(0xFF4EA8FF),
    onPrimary = Color(0xFF04121F),
    background = Ink.Background,
    onBackground = Ink.Primary,
    surface = Ink.Surface,
    onSurface = Ink.Primary,
    surfaceVariant = Ink.SurfaceElevated,
    onSurfaceVariant = Ink.Secondary,
    outline = Ink.Outline,
    error = Ink.Danger,
)

private val AiceTypography = Typography(
    displayLarge = TextStyle(fontSize = 88.sp, fontWeight = FontWeight.Thin, letterSpacing = (-4).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AiceTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dark only — a backlit white screen on a device you wear at night is a bug.
    MaterialTheme(colorScheme = AiceColors, typography = AiceTypography, content = content)
}
