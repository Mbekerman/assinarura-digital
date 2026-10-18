package br.com.bkm.assinapdf.stamps

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Tipo visual do carimbo. A proporção largura/altura é fixa por tipo para o redimensionamento preservar as proporções. */
@Serializable
enum class StampKind(val aspectRatio: Double) {
    /** Carimbo padrão de assinatura digital: 3 × 1 (por exemplo 42 × 14 mm em A4 a 20 %). */
    SIGNATURE(3.0),
}

/**
 * Um carimbo visual sobre uma página.
 *
 * Posição e tamanho são normalizados em relação à página exibida: [centerX] e [centerY] variam
 * de 0 a 1 (0,0 no canto superior esquerdo), e [widthFraction] é a largura relativa à largura da
 * página exibida. Assim o carimbo independe de resolução de tela, zoom e tamanho de bitmap, e a
 * mesma fração produz carimbos fisicamente proporcionais em A4 e em A0.
 */
@Serializable
data class Stamp(
    val id: String = newId(),
    val pageIndex: Int,
    val centerX: Double,
    val centerY: Double,
    val widthFraction: Double,
    /** Rotação em graus, sentido horário na tela, normalizada para [0, 360). */
    val rotationDeg: Double = 0.0,
    val kind: StampKind = StampKind.SIGNATURE,
) {
    val aspectRatio: Double get() = kind.aspectRatio

    fun widthPt(page: PageGeometry): Double = widthFraction * page.displayWidthPt
    fun heightPt(page: PageGeometry): Double = widthPt(page) / aspectRatio
    fun widthMm(page: PageGeometry): Double = page.ptToMm(widthPt(page))
    fun heightMm(page: PageGeometry): Double = page.ptToMm(heightPt(page))

    /** Centro em pontos do espaço de exibição. */
    fun centerPt(page: PageGeometry): Pair<Double, Double> =
        Pair(centerX * page.displayWidthPt, centerY * page.displayHeightPt)

    /** Os quatro cantos (em pt de exibição), na ordem: superior esquerdo, superior direito, inferior direito, inferior esquerdo. */
    fun cornersPt(page: PageGeometry): List<Pair<Double, Double>> {
        val (cx, cy) = centerPt(page)
        val hw = widthPt(page) / 2
        val hh = heightPt(page) / 2
        val rad = Math.toRadians(rotationDeg)
        val c = cos(rad)
        val s = sin(rad)
        fun rot(lx: Double, ly: Double) = Pair(cx + lx * c - ly * s, cy + lx * s + ly * c)
        return listOf(rot(-hw, -hh), rot(hw, -hh), rot(hw, hh), rot(-hw, hh))
    }

    /** Caixa envolvente (em pt de exibição) considerando a rotação. */
    fun boundsPt(page: PageGeometry): Bounds {
        val corners = cornersPt(page)
        return Bounds(
            left = corners.minOf { it.first },
            top = corners.minOf { it.second },
            right = corners.maxOf { it.first },
            bottom = corners.maxOf { it.second },
        )
    }

    /** Converte um ponto do espaço de exibição para o referencial local do carimbo (centro na origem, sem rotação). */
    fun toLocal(page: PageGeometry, xd: Double, yd: Double): Pair<Double, Double> {
        val (cx, cy) = centerPt(page)
        val dx = xd - cx
        val dy = yd - cy
        val rad = Math.toRadians(-rotationDeg)
        val c = cos(rad)
        val s = sin(rad)
        return Pair(dx * c - dy * s, dx * s + dy * c)
    }

    /** Verdadeiro se o ponto (pt de exibição) está dentro do carimbo, com [tolerancePt] de folga ao redor. */
    fun contains(page: PageGeometry, xd: Double, yd: Double, tolerancePt: Double = 0.0): Boolean {
        val (lx, ly) = toLocal(page, xd, yd)
        return abs(lx) <= widthPt(page) / 2 + tolerancePt && abs(ly) <= heightPt(page) / 2 + tolerancePt
    }

    fun withRotation(deg: Double): Stamp = copy(rotationDeg = normalizeDegrees(deg))

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun normalizeDegrees(deg: Double): Double {
            var d = deg % 360.0
            if (d < 0) d += 360.0
            if (d >= 360.0) d -= 360.0
            // evita -0.0 e ruído numérico
            return if (abs(d) < 1e-9 || abs(d - 360.0) < 1e-9) 0.0 else d
        }
    }
}

@Serializable
data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2
}

/** Regras de limites, encaixe e ajuste magnético dos carimbos. */
object StampRules {
    /** Menor largura permitida, como fração da página (1 %). */
    const val MIN_WIDTH_FRACTION = 0.01

    /** Maior largura permitida (a largura inteira da página). */
    const val MAX_WIDTH_FRACTION = 1.0

    /** Ângulos em que a rotação "gruda" e a folga, em graus. */
    val SNAP_ANGLES = listOf(0.0, 90.0, 180.0, 270.0, 360.0)
    const val SNAP_TOLERANCE_DEG = 4.0

    fun clampWidthFraction(f: Double): Double = f.coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION)

    /** Aproxima o ângulo de 0°, 90°, 180° ou 270° quando estiver a até [SNAP_TOLERANCE_DEG] deles. */
    fun snapRotation(deg: Double, tolerance: Double = SNAP_TOLERANCE_DEG): Double {
        val n = Stamp.normalizeDegrees(deg)
        val target = SNAP_ANGLES.firstOrNull { abs(n - it) <= tolerance }
        return if (target != null) Stamp.normalizeDegrees(target) else n
    }

    /**
     * Garante que o carimbo nunca se perca fora da página: o centro fica sempre dentro da área
     * visível, com uma pequena margem interna. O carimbo pode ficar parcialmente fora (por exemplo
     * para alcançar o canto de um carimbo técnico), mas sempre há parte dele visível.
     */
    fun keepCenterInside(stamp: Stamp, marginFraction: Double = 0.01): Stamp {
        val m = marginFraction.coerceIn(0.0, 0.49)
        return stamp.copy(
            centerX = stamp.centerX.coerceIn(m, 1.0 - m),
            centerY = stamp.centerY.coerceIn(m, 1.0 - m),
        )
    }

    /**
     * Ajusta o carimbo para caber inteiramente dentro da página, considerando as dimensões
     * rotacionadas: reduz a largura se necessário e desloca o centro para dentro.
     */
    fun fitInsidePage(stamp: Stamp, page: PageGeometry): Stamp {
        var s = stamp
        val pw = page.displayWidthPt
        val ph = page.displayHeightPt
        var bounds = s.boundsPt(page)
        if (bounds.width > pw || bounds.height > ph) {
            val scale = min(pw / bounds.width, ph / bounds.height)
            s = s.copy(widthFraction = clampWidthFraction(s.widthFraction * scale * 0.999))
            bounds = s.boundsPt(page)
        }
        var dx = 0.0
        var dy = 0.0
        if (bounds.left < 0) dx = -bounds.left
        if (bounds.right > pw) dx = pw - bounds.right
        if (bounds.top < 0) dy = -bounds.top
        if (bounds.bottom > ph) dy = ph - bounds.bottom
        if (dx != 0.0 || dy != 0.0) {
            s = s.copy(centerX = s.centerX + dx / pw, centerY = s.centerY + dy / ph)
        }
        return s
    }

    /** Verdadeiro se a caixa envolvente rotacionada está inteiramente dentro da página. */
    fun isFullyInside(stamp: Stamp, page: PageGeometry): Boolean {
        val b = stamp.boundsPt(page)
        return b.left >= -1e-6 && b.top >= -1e-6 && b.right <= page.displayWidthPt + 1e-6 && b.bottom <= page.displayHeightPt + 1e-6
    }

    /** Largura máxima (fração) que ainda cabe na página para um dado ângulo e proporção. */
    fun maxWidthFractionFor(page: PageGeometry, rotationDeg: Double, aspectRatio: Double): Double {
        val rad = Math.toRadians(rotationDeg)
        val c = abs(cos(rad))
        val s = abs(sin(rad))
        // largura da caixa envolvente para largura w e altura w/aspect: w*(c + s/aspect); altura: w*(s + c/aspect)
        val w1 = page.displayWidthPt / (c + s / aspectRatio)
        val w2 = page.displayHeightPt / (s + c / aspectRatio)
        return clampWidthFraction(max(MIN_WIDTH_FRACTION, min(w1, w2) / page.displayWidthPt))
    }
}
