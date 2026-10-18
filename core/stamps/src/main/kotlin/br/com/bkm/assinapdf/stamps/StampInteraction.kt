package br.com.bkm.assinapdf.stamps

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Alças de transformação exibidas ao redor do carimbo selecionado. */
enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT, ROTATE }

/** Cálculos de interação (toque, arraste, alças) no espaço de exibição da página, em pt. */
object StampInteraction {

    /** Distância (pt de exibição) da borda superior do carimbo até a alça de rotação. */
    fun rotateHandleOffsetPt(handleOffsetPx: Double, viewport: Viewport): Double = handleOffsetPx / viewport.scale

    /** Posições das alças em pt de exibição. */
    fun handlePositions(stamp: Stamp, page: PageGeometry, rotateOffsetPt: Double): Map<Handle, Pair<Double, Double>> {
        val corners = stamp.cornersPt(page)
        val (cx, cy) = stamp.centerPt(page)
        val hh = stamp.heightPt(page) / 2
        val rad = Math.toRadians(stamp.rotationDeg)
        // a alça de rotação fica acima do meio da borda superior, no referencial girado
        val lx = 0.0
        val ly = -(hh + rotateOffsetPt)
        val rx = cx + lx * Math.cos(rad) - ly * Math.sin(rad)
        val ry = cy + lx * Math.sin(rad) + ly * Math.cos(rad)
        return mapOf(
            Handle.TOP_LEFT to corners[0],
            Handle.TOP_RIGHT to corners[1],
            Handle.BOTTOM_RIGHT to corners[2],
            Handle.BOTTOM_LEFT to corners[3],
            Handle.ROTATE to Pair(rx, ry),
        )
    }

    /** Alça atingida pelo toque, se houver. [radiusPt] é o raio de toque já convertido para pt. */
    fun hitHandle(stamp: Stamp, page: PageGeometry, xd: Double, yd: Double, radiusPt: Double, rotateOffsetPt: Double): Handle? {
        return handlePositions(stamp, page, rotateOffsetPt).entries
            .map { (h, p) -> h to hypot(p.first - xd, p.second - yd) }
            .filter { it.second <= radiusPt }
            .minByOrNull { it.second }
            ?.first
    }

    /** Carimbo tocado: o último da lista (desenhado por cima) tem prioridade. */
    fun hitStamp(stamps: List<Stamp>, page: PageGeometry, xd: Double, yd: Double, tolerancePt: Double): Stamp? =
        stamps.asReversed().firstOrNull { it.pageIndex == page.index && it.contains(page, xd, yd, tolerancePt) }

    /** Move o carimbo em um deslocamento em pt de exibição, mantendo o centro dentro da página. */
    fun moved(stamp: Stamp, page: PageGeometry, dxPt: Double, dyPt: Double): Stamp {
        val moved = stamp.copy(
            centerX = stamp.centerX + dxPt / page.displayWidthPt,
            centerY = stamp.centerY + dyPt / page.displayHeightPt,
        )
        return StampRules.keepCenterInside(moved)
    }

    /**
     * Redimensiona proporcionalmente arrastando um canto: a nova largura é a que faz o canto
     * acompanhar o dedo, medida no referencial local do carimbo. O centro permanece fixo.
     */
    fun resizedByCorner(stamp: Stamp, page: PageGeometry, pointerXd: Double, pointerYd: Double): Stamp {
        val (lx, ly) = stamp.toLocal(page, pointerXd, pointerYd)
        val widthFromX = 2 * abs(lx)
        val widthFromY = 2 * abs(ly) * stamp.aspectRatio
        val newWidthPt = max(widthFromX, widthFromY)
        val fraction = StampRules.clampWidthFraction(newWidthPt / page.displayWidthPt)
        return stamp.copy(widthFraction = fraction)
    }

    /**
     * Rotaciona pelo arraste da alça: o ângulo é o do vetor centro→dedo, corrigido em 90° porque a
     * alça fica acima do carimbo. Aplica ajuste magnético perto de 0°, 90°, 180° e 270°.
     */
    fun rotatedByHandle(stamp: Stamp, page: PageGeometry, pointerXd: Double, pointerYd: Double, snap: Boolean = true): Stamp {
        val (cx, cy) = stamp.centerPt(page)
        val angle = Math.toDegrees(atan2(pointerYd - cy, pointerXd - cx)) + 90.0
        val normalized = Stamp.normalizeDegrees(angle)
        return stamp.withRotation(if (snap) StampRules.snapRotation(normalized) else normalized)
    }

    /** Rotação por gesto de dois dedos: soma o delta ao ângulo atual (sem ajuste magnético durante o gesto). */
    fun rotatedBy(stamp: Stamp, deltaDeg: Double): Stamp = stamp.withRotation(stamp.rotationDeg + deltaDeg)

    /** Escala por gesto de pinça sobre o carimbo. */
    fun scaledBy(stamp: Stamp, factor: Double): Stamp =
        stamp.copy(widthFraction = StampRules.clampWidthFraction(stamp.widthFraction * factor))
}
