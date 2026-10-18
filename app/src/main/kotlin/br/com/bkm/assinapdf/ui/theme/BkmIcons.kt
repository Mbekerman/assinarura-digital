package br.com.bkm.assinapdf.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Ícones de interface no estilo do kit BKM: desenho em linha, cantos e pontas arredondados.
 * O kit oficial (96×96, traço 3) é ilustrativo; aqui a mesma linguagem em grade 24 para barras.
 */
object BkmIcons {
    private fun icon(name: String, stroke: Float = 1.8f, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, false, true, cx - r, cy)
        arcTo(r, r, 0f, false, true, cx + r, cy)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        arcTo(rad, rad, 0f, false, true, r, t + rad)
        lineTo(r, b - rad)
        arcTo(rad, rad, 0f, false, true, r - rad, b)
        lineTo(l + rad, b)
        arcTo(rad, rad, 0f, false, true, l, b - rad)
        lineTo(l, t + rad)
        arcTo(rad, rad, 0f, false, true, l + rad, t)
        close()
    }

    val Voltar = icon("voltar") { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
    val Seta = icon("seta") { moveTo(4f, 12f); lineTo(19f, 12f); moveTo(13f, 6f); lineTo(19f, 12f); lineTo(13f, 18f) }
    val Desfazer = icon("desfazer") {
        moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f)
        moveTo(4f, 9f); lineTo(15f, 9f); curveTo(17.76f, 9f, 20f, 11.24f, 20f, 14f); curveTo(20f, 16.76f, 17.76f, 19f, 15f, 19f); lineTo(11f, 19f)
    }
    val Refazer = icon("refazer") {
        moveTo(15f, 14f); lineTo(20f, 9f); lineTo(15f, 4f)
        moveTo(20f, 9f); lineTo(9f, 9f); curveTo(6.24f, 9f, 4f, 11.24f, 4f, 14f); curveTo(4f, 16.76f, 6.24f, 19f, 9f, 19f); lineTo(13f, 19f)
    }
    val GirarDireita = icon("girar_direita") {
        moveTo(12f, 20f); arcTo(7f, 7f, 0f, true, true, 19f, 13f)
        moveTo(16.5f, 10.5f); lineTo(19f, 13f); lineTo(21.5f, 10.5f)
    }
    val GirarEsquerda = icon("girar_esquerda") {
        moveTo(12f, 20f); arcTo(7f, 7f, 0f, true, false, 5f, 13f)
        moveTo(2.5f, 10.5f); lineTo(5f, 13f); lineTo(7.5f, 10.5f)
    }
    val Excluir = icon("excluir") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(10f, 4f); lineTo(14f, 4f)
        moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
        moveTo(10f, 11f); lineTo(10f, 16f); moveTo(14f, 11f); lineTo(14f, 16f)
    }
    val Duplicar = icon("duplicar") {
        roundRect(9f, 9f, 20f, 20f, 2f)
        moveTo(5f, 15f); lineTo(4f, 15f); lineTo(4f, 4f); lineTo(15f, 4f); lineTo(15f, 5f)
    }
    val Compartilhar = icon("compartilhar") {
        circle(18f, 5f, 2.5f); circle(6f, 12f, 2.5f); circle(18f, 19f, 2.5f)
        moveTo(8.2f, 10.8f); lineTo(15.8f, 6.2f); moveTo(8.2f, 13.2f); lineTo(15.8f, 17.8f)
    }
    val Salvar = icon("salvar") { moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f); moveTo(5f, 20f); lineTo(19f, 20f) }
    val Pasta = icon("pasta") {
        moveTo(3f, 7f); lineTo(3f, 18f); curveTo(3f, 19.1f, 3.9f, 20f, 5f, 20f); lineTo(19f, 20f); curveTo(20.1f, 20f, 21f, 19.1f, 21f, 18f)
        lineTo(21f, 9f); curveTo(21f, 7.9f, 20.1f, 7f, 19f, 7f); lineTo(12f, 7f); lineTo(10f, 5f); lineTo(5f, 5f); curveTo(3.9f, 5f, 3f, 5.9f, 3f, 7f); close()
    }
    val Ajustes = icon("ajustes") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
        circle(9f, 7f, 2f); circle(15f, 12f, 2f); circle(8f, 17f, 2f)
    }
    val AjustarLargura = icon("ajustar_largura") {
        moveTo(4f, 6f); lineTo(4f, 18f); moveTo(20f, 6f); lineTo(20f, 18f); moveTo(7f, 12f); lineTo(17f, 12f)
        moveTo(9.5f, 9.5f); lineTo(7f, 12f); lineTo(9.5f, 14.5f); moveTo(14.5f, 9.5f); lineTo(17f, 12f); lineTo(14.5f, 14.5f)
    }
    val AjustarPagina = icon("ajustar_pagina") {
        moveTo(4f, 9f); lineTo(4f, 5f); lineTo(8f, 5f); moveTo(16f, 5f); lineTo(20f, 5f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(20f, 19f); lineTo(16f, 19f); moveTo(8f, 19f); lineTo(4f, 19f); lineTo(4f, 15f)
        roundRect(8.5f, 8f, 15.5f, 16f, 1f)
    }
    val Carimbo = icon("carimbo") {
        roundRect(2.5f, 8f, 16.5f, 17f, 2f)
        moveTo(5.5f, 12.5f); lineTo(7f, 14f); lineTo(9.5f, 11f)
        moveTo(19.5f, 3f); lineTo(19.5f, 9f); moveTo(16.5f, 6f); lineTo(22.5f, 6f)
    }
    val Confirmar = icon("confirmar") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val Fechar = icon("fechar") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Documento = icon("documento") {
        moveTo(7f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(7f, 21f); close()
        moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
    }
    val Escudo = icon("escudo") {
        moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); curveTo(19f, 16f, 16f, 19.5f, 12f, 21f); curveTo(8f, 19.5f, 5f, 16f, 5f, 11f); lineTo(5f, 6f); close()
        moveTo(9f, 12f); lineTo(11f, 14f); lineTo(15f, 10f)
    }
    val Encaixar = icon("encaixar") {
        roundRect(3f, 4f, 21f, 20f, 2f)
        moveTo(8f, 9f); lineTo(10.5f, 11.5f); moveTo(8f, 9f); lineTo(8f, 11f); moveTo(8f, 9f); lineTo(10f, 9f)
        moveTo(16f, 15f); lineTo(13.5f, 12.5f); moveTo(16f, 15f); lineTo(16f, 13f); moveTo(16f, 15f); lineTo(14f, 15f)
    }
    val Restaurar = icon("restaurar") {
        moveTo(5f, 12f); arcTo(7f, 7f, 0f, true, false, 7.05f, 7.05f)
        moveTo(4f, 5f); lineTo(4f, 9f); lineTo(8f, 9f)
    }
    val Mais = icon("mais", stroke = 2.4f) {
        moveTo(12f, 5f); lineTo(12f, 5.01f); moveTo(12f, 12f); lineTo(12f, 12.01f); moveTo(12f, 19f); lineTo(12f, 19.01f)
    }
    val Paginas = icon("paginas") {
        roundRect(7f, 3f, 19f, 17f, 1.5f)
        moveTo(5f, 7f); lineTo(5f, 21f); lineTo(15f, 21f)
    }
    val Alerta = icon("alerta") {
        moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
        moveTo(12f, 10f); lineTo(12f, 14f); moveTo(12f, 17f); lineTo(12f, 17.01f)
    }

    /** Símbolo casa da BKM (formas do arquivo vetorial oficial, como no design system). */
    fun simboloCasa(brush: Brush): ImageVector = ImageVector.Builder("simbolo_casa", 186.dp, 200.dp, 185.959f, 200f).apply {
        path(fill = brush) {
            moveTo(185.942f, 173.768f); lineTo(185.942f, 149.34f); lineTo(0.01f, 149.34f); lineTo(0.01f, 173.768f)
            curveTo(0.01f, 188.231f, 10.602f, 200f, 23.618f, 200f); lineTo(162.334f, 200f)
            curveTo(175.35f, 200f, 185.942f, 188.231f, 185.942f, 173.768f); close()
        }
        path(fill = brush) {
            moveTo(92.991f, 62.504f); lineTo(91.606f, 63.798f); lineTo(30.864f, 120.559f); lineTo(0.01f, 150.693f); lineTo(0.01f, 105.255f)
            lineTo(0.017f, 105.255f); curveTo(0f, 94.355f, 2.714f, 85.167f, 12.365f, 75.462f); lineTo(92.702f, 0f); lineTo(125.52f, 30.581f)
            lineTo(173.594f, 75.462f); curveTo(183.244f, 85.167f, 185.959f, 94.355f, 185.942f, 105.255f); lineTo(185.942f, 150.693f)
            lineTo(155.095f, 120.559f); lineTo(94.353f, 63.798f); lineTo(92.968f, 62.504f); close()
        }
    }.build()

    /** Gradiente da marca: ciano → azul-bkm (55%) → profundo. */
    val gradienteMarca: Brush = Brush.linearGradient(
        0f to Bkm.GradienteCiano, 0.55f to Bkm.AzulBkm, 1f to Bkm.GradienteProfundo,
        start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(186f, 0f),
    )
}
