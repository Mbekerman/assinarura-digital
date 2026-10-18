package br.com.bkm.assinapdf.stamps

import kotlin.math.cos
import kotlin.math.sin

/**
 * Colocação de um carimbo no espaço do usuário do PDF, pronta para gerar o operador `cm`.
 *
 * O carimbo é desenhado como um Form XObject com BBox [0 0 DESIGN_WIDTH DESIGN_HEIGHT]; a matriz
 * leva esse desenho para o centro, a escala e a rotação corretos na página.
 */
data class PdfPlacement(
    val pageIndex: Int,
    /** Centro em espaço do usuário (pt). */
    val centerXUser: Double,
    val centerYUser: Double,
    val widthPt: Double,
    val heightPt: Double,
    /** Ângulo no espaço do usuário em graus, anti-horário positivo (convenção PDF). */
    val angleDegUser: Double,
    /** Matriz [a b c d e f] do operador cm que posiciona o Form XObject de desenho. */
    val matrix: DoubleArray,
) {
    val a get() = matrix[0]
    val b get() = matrix[1]
    val c get() = matrix[2]
    val d get() = matrix[3]
    val e get() = matrix[4]
    val f get() = matrix[5]
}

object StampPlacement {
    /** Dimensões do espaço de desenho do carimbo (unidades arbitrárias, proporção 3:1). */
    const val DESIGN_WIDTH = 300.0
    const val DESIGN_HEIGHT = 100.0

    /**
     * Calcula a colocação do carimbo no PDF.
     *
     * Regra de rotação: o ângulo do carimbo é medido no sentido horário na tela. No espaço do
     * usuário (Y para cima, anti-horário positivo) isso vira `-θ`; como a página pode ter /Rotate R
     * (girada R graus no sentido horário para exibição), um objeto que aparece reto na tela está
     * girado +R no espaço do usuário. Logo: ângulo PDF = R − θ.
     */
    fun place(stamp: Stamp, page: PageGeometry): PdfPlacement {
        require(stamp.pageIndex == page.index) { "carimbo da página ${stamp.pageIndex} aplicado à página ${page.index}" }
        val (cxd, cyd) = stamp.centerPt(page)
        val (cxu, cyu) = page.displayToUser(cxd, cyd)
        val widthPt = stamp.widthPt(page)
        val heightPt = stamp.heightPt(page)
        val angle = Stamp.normalizeDegrees(page.rotation - stamp.rotationDeg)
        val designW = DESIGN_WIDTH
        val designH = DESIGN_WIDTH / stamp.aspectRatio
        val scale = widthPt / designW
        val rad = Math.toRadians(angle)
        val cosA = cos(rad)
        val sinA = sin(rad)
        // p' = R(S(p − o)) + C, com o = centro do desenho
        val a = scale * cosA
        val b = scale * sinA
        val c = -scale * sinA
        val d = scale * cosA
        val ox = designW / 2
        val oy = designH / 2
        val e = cxu - a * ox - c * oy
        val f = cyu - b * ox - d * oy
        return PdfPlacement(
            pageIndex = page.index,
            centerXUser = cxu,
            centerYUser = cyu,
            widthPt = widthPt,
            heightPt = heightPt,
            angleDegUser = angle,
            matrix = doubleArrayOf(a, b, c, d, e, f),
        )
    }

    /**
     * Ponto do desenho (Y para baixo, como em [StampVisual]) para o espaço de exibição da página.
     * É a mesma transformação que a pré-visualização aplica no Canvas: transladar ao centro,
     * girar θ no sentido horário e escalar largura/DESIGN_WIDTH.
     */
    fun designToDisplay(stamp: Stamp, page: PageGeometry, x: Double, y: Double): Pair<Double, Double> {
        val (cx, cy) = stamp.centerPt(page)
        val scale = stamp.widthPt(page) / DESIGN_WIDTH
        val designH = DESIGN_WIDTH / stamp.aspectRatio
        val lx = (x - DESIGN_WIDTH / 2) * scale
        val ly = (y - designH / 2) * scale
        val rad = Math.toRadians(stamp.rotationDeg)
        return Pair(cx + lx * cos(rad) - ly * sin(rad), cy + lx * sin(rad) + ly * cos(rad))
    }

    /** Aplica a matriz a um ponto do espaço de desenho; útil em testes e verificações. */
    fun apply(matrix: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val (a, b, c, d, e, f) = matrix.toList()
        return Pair(a * x + c * y + e, b * x + d * y + f)
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}
