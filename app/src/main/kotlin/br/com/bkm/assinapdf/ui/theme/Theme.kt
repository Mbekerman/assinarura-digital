package br.com.bkm.assinapdf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val LocalBkmColors = staticCompositionLocalOf { ClaroColors }

private fun scheme(c: BkmColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.acao,
        onPrimary = c.sobreAcao,
        primaryContainer = c.superficieElevada,
        onPrimaryContainer = c.texto,
        secondary = c.destaque,
        onSecondary = Bkm.Branco,
        secondaryContainer = c.superficieElevada,
        onSecondaryContainer = c.texto,
        tertiary = c.textoDestaque,
        onTertiary = c.superficie,
        background = c.superficie,
        onBackground = c.texto,
        surface = c.superficie,
        onSurface = c.texto,
        surfaceVariant = c.superficieElevada,
        onSurfaceVariant = c.textoSuave,
        surfaceContainerLowest = c.superficie,
        surfaceContainerLow = c.superficie,
        surfaceContainer = c.superficieElevada,
        surfaceContainerHigh = c.superficieElevada,
        surfaceContainerHighest = c.superficieElevada,
        outline = c.bordaControle,
        outlineVariant = c.linha,
        // O design system não define cor de erro: usamos terracota (ícones e bordas) e pêssego como
        // fundo com texto marinho (6,7:1), combinações que o próprio sistema documenta.
        error = Bkm.Terracota,
        onError = Bkm.Branco,
        errorContainer = Bkm.Pessego,
        onErrorContainer = Bkm.Marinho,
    )
}

// Aptos é licenciada pela Microsoft e não está empacotada; a pilha cai para a sans do sistema,
// como prevê o design system. Sem manuscrita em telas de trabalho.
private val Sans = FontFamily.SansSerif

val BkmTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    // "rotulo": caixa alta, espaçado
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.18.em),
)

/** raio-sm 4, raio-md 8, raio-botao 12, raio-lg 20, raio-xl 24 */
val BkmShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AssinaPdfTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) MarinhoColors else ClaroColors
    CompositionLocalProvider(LocalBkmColors provides colors) {
        MaterialTheme(colorScheme = scheme(colors, dark), typography = BkmTypography, shapes = BkmShapes, content = content)
    }
}

object BkmTheme {
    val colors: BkmColors @Composable get() = LocalBkmColors.current
}
