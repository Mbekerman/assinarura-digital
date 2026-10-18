package br.com.bkm.assinapdf.ui.theme

import androidx.compose.ui.graphics.Color

/** Cores do design system BKM (project/tokens.json). */
object Bkm {
    val Marinho = Color(0xFF1D2E5C)
    val CinzaEscuro = Color(0xFF43464B)
    val Preto = Color(0xFF000000)
    val Branco = Color(0xFFFFFFFF)
    val AzulBkm = Color(0xFF0094FE)
    val Pessego = Color(0xFFDEAFA1)
    val Roxo = Color(0xFF863D8F)
    val Terracota = Color(0xFFC67355)
    val VerdeClaro = Color(0xFFE8EED2)
    val VerdeSalvia = Color(0xFFCCD3A7)
    val CinzaClaro = Color(0xFFCECECC)
    val GradienteCiano = Color(0xFF0EC2FF)
    val GradienteProfundo = Color(0xFF1459C3)
    val MarinhoProfundo = Color(0xFF23315E)
    val AzulGelo = Color(0xFFE4EBF8)
}

/** Tokens semânticos por tema (Claro e Marinho). */
data class BkmColors(
    val superficie: Color,
    val superficieElevada: Color,
    val texto: Color,
    val textoSuave: Color,
    val textoDestaque: Color,
    val destaque: Color,
    val linha: Color,
    val bordaControle: Color,
    val foco: Color,
    val acao: Color,
    val sobreAcao: Color,
    /** Fundo atrás das páginas no visualizador. */
    val fundoVisualizador: Color,
)

val ClaroColors = BkmColors(
    superficie = Bkm.Branco,
    superficieElevada = Bkm.AzulGelo,
    texto = Bkm.Marinho,
    textoSuave = Bkm.CinzaEscuro,
    textoDestaque = Bkm.GradienteProfundo,
    destaque = Bkm.AzulBkm,
    linha = Bkm.CinzaClaro,
    bordaControle = Bkm.CinzaEscuro,
    foco = Bkm.GradienteProfundo,
    acao = Bkm.Marinho,
    sobreAcao = Bkm.Branco,
    fundoVisualizador = Bkm.AzulGelo,
)

val MarinhoColors = BkmColors(
    superficie = Bkm.Marinho,
    superficieElevada = Bkm.MarinhoProfundo,
    texto = Bkm.Branco,
    textoSuave = Bkm.CinzaClaro,
    textoDestaque = Bkm.GradienteCiano,
    destaque = Bkm.AzulBkm,
    linha = Color(0x800094FE),
    bordaControle = Bkm.CinzaClaro,
    foco = Bkm.GradienteCiano,
    acao = Bkm.Branco,
    sobreAcao = Bkm.Marinho,
    fundoVisualizador = Bkm.MarinhoProfundo,
)
