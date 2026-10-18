package br.com.bkm.assinapdf.stamps

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Retângulo em pontos PDF (1/72 pol), com origem no canto inferior esquerdo, como MediaBox e CropBox. */
@Serializable
data class PdfBox(val llx: Double, val lly: Double, val urx: Double, val ury: Double) {
    val width: Double get() = urx - llx
    val height: Double get() = ury - lly

    /** Normaliza caixas escritas com cantos invertidos (permitido pela especificação PDF). */
    fun normalized(): PdfBox = PdfBox(min(llx, urx), min(lly, ury), max(llx, urx), max(lly, ury))

    fun intersect(other: PdfBox): PdfBox? {
        val a = normalized()
        val b = other.normalized()
        val llx = max(a.llx, b.llx)
        val lly = max(a.lly, b.lly)
        val urx = min(a.urx, b.urx)
        val ury = min(a.ury, b.ury)
        return if (urx > llx && ury > lly) PdfBox(llx, lly, urx, ury) else null
    }

    companion object {
        fun ofSize(width: Double, height: Double) = PdfBox(0.0, 0.0, width, height)
    }
}

/** Formatos de papel reconhecidos a partir das dimensões físicas da página. */
enum class PaperFormat(val label: String, val shortMm: Double, val longMm: Double) {
    A0("A0", 841.0, 1189.0),
    A1("A1", 594.0, 841.0),
    A2("A2", 420.0, 594.0),
    A3("A3", 297.0, 420.0),
    A4("A4", 210.0, 297.0),
    A5("A5", 148.0, 210.0),
    LETTER("Carta", 215.9, 279.4),
    LEGAL("Ofício", 215.9, 355.6),
    CUSTOM("Personalizado", 0.0, 0.0);

    companion object {
        /**
         * Identifica o formato pelo par de dimensões em milímetros, independentemente da orientação.
         * Tolerância: 1,5 % de cada lado ou 3 mm, o que for maior (PDFs gerados por CAD costumam
         * ter décimos de milímetro de diferença).
         */
        fun detect(widthMm: Double, heightMm: Double): PaperFormat {
            val short = min(widthMm, heightMm)
            val long = max(widthMm, heightMm)
            return entries.firstOrNull { f ->
                f != CUSTOM && within(short, f.shortMm) && within(long, f.longMm)
            } ?: CUSTOM
        }

        private fun within(value: Double, expected: Double): Boolean {
            val tolerance = max(expected * 0.015, 3.0)
            return abs(value - expected) <= tolerance
        }
    }
}

/**
 * Geometria real de uma página do PDF, lida das caixas do documento.
 *
 * Toda a geometria dos carimbos é expressa no "espaço de exibição" da página: a área visível
 * (CropBox ∩ MediaBox) já girada pelo /Rotate, com origem no canto superior esquerdo e eixo Y para
 * baixo, medida em pontos PDF. Esse é o espaço que o usuário vê na tela, e é dele que se calcula a
 * matriz de colocação no espaço do usuário do PDF na hora de exportar.
 */
@Serializable
data class PageGeometry(
    val index: Int,
    val mediaBox: PdfBox,
    val cropBox: PdfBox = mediaBox,
    /** Rotação da página em graus, já normalizada para 0, 90, 180 ou 270. */
    val rotation: Int = 0,
    /** /UserUnit da página: múltiplo de 1/72 pol que cada unidade representa (padrão 1,0). */
    val userUnit: Double = 1.0,
) {
    init {
        require(rotation in setOf(0, 90, 180, 270)) { "rotação inválida: $rotation" }
        require(userUnit > 0) { "UserUnit deve ser positivo" }
    }

    /** Área efetivamente visível em espaço do usuário (sem rotação). */
    val visibleBox: PdfBox = cropBox.intersect(mediaBox) ?: mediaBox.normalized()

    val displayWidthPt: Double get() = if (rotation % 180 == 0) visibleBox.width else visibleBox.height
    val displayHeightPt: Double get() = if (rotation % 180 == 0) visibleBox.height else visibleBox.width

    val widthMm: Double get() = ptToMm(displayWidthPt)
    val heightMm: Double get() = ptToMm(displayHeightPt)

    val paperFormat: PaperFormat get() = PaperFormat.detect(widthMm, heightMm)
    val isLandscape: Boolean get() = displayWidthPt > displayHeightPt

    /** Converte uma medida em pontos desta página para milímetros físicos (considera UserUnit). */
    fun ptToMm(pt: Double): Double = pt * userUnit * MM_PER_PT

    fun mmToPt(mm: Double): Double = mm / (userUnit * MM_PER_PT)

    /** Descrição curta, por exemplo "A1 · 594 × 841 mm". */
    fun describe(): String {
        val fmt = paperFormat
        val dims = "${formatMm(widthMm)} × ${formatMm(heightMm)} mm"
        return if (fmt == PaperFormat.CUSTOM) dims else "${fmt.label} · $dims"
    }

    /**
     * Converte um ponto do espaço de exibição (pt, origem no canto superior esquerdo, Y para baixo)
     * para o espaço do usuário do PDF (origem da página, Y para cima), levando em conta a rotação
     * e o deslocamento da área visível.
     */
    fun displayToUser(xd: Double, yd: Double): Pair<Double, Double> {
        val v = visibleBox
        return when (rotation) {
            0 -> Pair(v.llx + xd, v.ury - yd)
            90 -> Pair(v.llx + yd, v.lly + xd)
            180 -> Pair(v.urx - xd, v.lly + yd)
            else -> Pair(v.urx - yd, v.ury - xd) // 270
        }
    }

    /** Inverso de [displayToUser]. */
    fun userToDisplay(xu: Double, yu: Double): Pair<Double, Double> {
        val v = visibleBox
        return when (rotation) {
            0 -> Pair(xu - v.llx, v.ury - yu)
            90 -> Pair(yu - v.lly, xu - v.llx)
            180 -> Pair(v.urx - xu, yu - v.lly)
            else -> Pair(v.ury - yu, v.urx - xu) // 270
        }
    }

    companion object {
        const val MM_PER_PT: Double = 25.4 / 72.0

        fun normalizeRotation(raw: Int): Int {
            val r = ((raw % 360) + 360) % 360
            return (Math.round(r / 90.0).toInt() * 90) % 360
        }

        fun formatMm(mm: Double): String {
            val rounded = Math.round(mm).toInt()
            return if (abs(mm - rounded) < 0.5) rounded.toString() else String.format(java.util.Locale.ROOT, "%.1f", mm)
        }
    }
}
