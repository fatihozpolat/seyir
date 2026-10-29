package seyir.app.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renk sözlüğü. Zemin neredeyse siyah (loş odada uzun süre bakılır); sıcak kehribar vurgu yalnız
 * birincil eylemde, ilerlemede ve seçimde kullanılır.
 */
object SeyirColors {
    val background = Color(0xFF0F0F12)
    val surface = Color(0xFF1C1C21)
    val surfaceHigh = Color(0xFF26262C)
    val surfaceHover = Color(0xFF2E2E35)
    val outline = Color(0xFF34343C)
    val hairline = Color(0x12FFFFFF)
    val text = Color(0xFFF4F4F6)
    val textSecondary = Color(0xFFC5C6CD)
    val textMuted = Color(0xFF8B8D98)
    val accent = Color(0xFFE0A33A)
    val accentPressed = Color(0xFFC98A26)
    val onAccent = Color(0xFF1A1305)
    val success = Color(0xFF4CC38A)
    val danger = Color(0xFFFF7A70)
}

/** Sayfa kenar boşluğu: başlıklar, sıralar ve listeler aynı dikey çizgiye oturur. */
val PageGutter = 40.dp

private val Inter = FontFamily(
    Font("fonts/Inter-Regular.ttf", FontWeight.Normal),
    Font("fonts/Inter-Medium.ttf", FontWeight.Medium),
    Font("fonts/Inter-SemiBold.ttf", FontWeight.SemiBold),
    Font("fonts/Inter-Bold.ttf", FontWeight.Bold),
)

/** Büyük başlıklar için Inter'in sıkı kesimli görüntü (display) boyu. */
private val InterDisplay = FontFamily(
    Font("fonts/InterDisplay-SemiBold.ttf", FontWeight.SemiBold),
    Font("fonts/InterDisplay-Bold.ttf", FontWeight.Bold),
    Font("fonts/InterDisplay-ExtraBold.ttf", FontWeight.ExtraBold),
)

private val colors = darkColorScheme(
    primary = SeyirColors.accent,
    onPrimary = SeyirColors.onAccent,
    primaryContainer = Color(0xFF3A2A10),
    onPrimaryContainer = SeyirColors.accent,
    secondary = SeyirColors.textSecondary,
    onSecondary = SeyirColors.background,
    background = SeyirColors.background,
    onBackground = SeyirColors.text,
    surface = SeyirColors.surface,
    onSurface = SeyirColors.text,
    surfaceVariant = SeyirColors.surfaceHigh,
    onSurfaceVariant = SeyirColors.textMuted,
    surfaceContainerLowest = SeyirColors.background,
    surfaceContainerLow = SeyirColors.surface,
    surfaceContainer = SeyirColors.surfaceHigh,
    surfaceContainerHigh = SeyirColors.surfaceHigh,
    surfaceContainerHighest = SeyirColors.surfaceHover,
    outline = SeyirColors.outline,
    outlineVariant = SeyirColors.hairline,
    error = SeyirColors.danger,
    onError = SeyirColors.background,
)

private fun style(family: FontFamily, weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private val typography = Typography(
    // Pano başlığı
    displayLarge = style(InterDisplay, FontWeight.ExtraBold, 56, 60, -1.9),
    // Detay başlığı
    displaySmall = style(InterDisplay, FontWeight.ExtraBold, 42, 46, -1.3),
    // Ekran başlıkları
    headlineMedium = style(InterDisplay, FontWeight.Bold, 30, 36, -0.6),
    headlineSmall = style(InterDisplay, FontWeight.Bold, 24, 30, -0.4),
    titleLarge = style(Inter, FontWeight.SemiBold, 20, 26, -0.25),
    titleMedium = style(Inter, FontWeight.SemiBold, 17, 24, -0.15),
    titleSmall = style(Inter, FontWeight.SemiBold, 14, 20),
    bodyLarge = style(Inter, FontWeight.Normal, 16, 24),
    bodyMedium = style(Inter, FontWeight.Normal, 14, 20),
    bodySmall = style(Inter, FontWeight.Normal, 13, 18),
    labelLarge = style(Inter, FontWeight.SemiBold, 14, 20),
    labelMedium = style(Inter, FontWeight.Medium, 13, 18),
    labelSmall = style(Inter, FontWeight.Medium, 12, 16, 0.1),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val scrollbarStyle = ScrollbarStyle(
    minimalHeight = 48.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 200,
    unhoverColor = Color.White.copy(alpha = 0.10f),
    hoverColor = Color.White.copy(alpha = 0.28f),
)

private val selectionColors = TextSelectionColors(
    handleColor = SeyirColors.accent,
    backgroundColor = SeyirColors.accent.copy(alpha = 0.32f),
)

/**
 * Material bileşenlerinin (menü öğeleri, anahtar, onay kutusu) klavye odağı katmanı varsayılanda
 * neredeyse görünmez; koyu zeminde okunsun diye yalnız odak şeffaflığı artırılır, renk ve diğer
 * durumlar aynı kalır.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val focusVisibleRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.24f, hoveredAlpha = 0.08f, pressedAlpha = 0.1f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeyirTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides scrollbarStyle,
            LocalTextSelectionColors provides selectionColors,
            LocalRippleConfiguration provides focusVisibleRipple,
            content = content,
        )
    }
}
