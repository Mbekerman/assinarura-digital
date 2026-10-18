package br.com.bkm.assinapdf.stamps

import kotlin.math.max
import kotlin.math.min

/**
 * Transformação entre o espaço de exibição de uma página (pt) e a tela (px).
 *
 * `screen = display * scale + offset`. O zoom do visualizador altera apenas [scale] e [offset];
 * nunca altera o carimbo, cuja geometria é guardada em frações da página.
 */
data class Viewport(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
) {
    fun toScreenX(xd: Double): Double = xd * scale + offsetX
    fun toScreenY(yd: Double): Double = yd * scale + offsetY
    fun toDisplayX(xs: Double): Double = (xs - offsetX) / scale
    fun toDisplayY(ys: Double): Double = (ys - offsetY) / scale

    fun toScreen(xd: Double, yd: Double): Pair<Double, Double> = Pair(toScreenX(xd), toScreenY(yd))
    fun toDisplay(xs: Double, ys: Double): Pair<Double, Double> = Pair(toDisplayX(xs), toDisplayY(ys))

    /** Novo viewport após aplicar um zoom [factor] mantendo o ponto focal ([fx], [fy], px) parado na tela. */
    fun zoomedAround(factor: Double, fx: Double, fy: Double, minScale: Double, maxScale: Double): Viewport {
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        val ratio = newScale / scale
        return Viewport(
            scale = newScale,
            offsetX = fx - (fx - offsetX) * ratio,
            offsetY = fy - (fy - offsetY) * ratio,
        )
    }

    fun panned(dx: Double, dy: Double): Viewport = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /**
     * Limita o deslocamento para a página não sair totalmente da área visível. Quando a página é
     * menor que a área, ela fica centralizada no eixo correspondente.
     */
    fun clampedTo(page: PageGeometry, viewWidth: Double, viewHeight: Double): Viewport {
        val pw = page.displayWidthPt * scale
        val ph = page.displayHeightPt * scale
        val ox = clampAxis(offsetX, pw, viewWidth)
        val oy = clampAxis(offsetY, ph, viewHeight)
        return copy(offsetX = ox, offsetY = oy)
    }

    private fun clampAxis(offset: Double, contentSize: Double, viewSize: Double): Double {
        return if (contentSize <= viewSize) {
            (viewSize - contentSize) / 2
        } else {
            offset.coerceIn(viewSize - contentSize, 0.0)
        }
    }

    companion object {
        /** Escala que ajusta a largura da página à largura da área, com margem opcional em px. */
        fun fitWidth(page: PageGeometry, viewWidth: Double, viewHeight: Double, paddingPx: Double = 0.0): Viewport {
            val scale = max(1e-6, (viewWidth - 2 * paddingPx) / page.displayWidthPt)
            return Viewport(scale, 0.0, 0.0).clampedTo(page, viewWidth, viewHeight)
        }

        /** Escala que mostra a página inteira dentro da área. */
        fun fitPage(page: PageGeometry, viewWidth: Double, viewHeight: Double, paddingPx: Double = 0.0): Viewport {
            val sx = (viewWidth - 2 * paddingPx) / page.displayWidthPt
            val sy = (viewHeight - 2 * paddingPx) / page.displayHeightPt
            val scale = max(1e-6, min(sx, sy))
            return Viewport(scale, 0.0, 0.0).clampedTo(page, viewWidth, viewHeight)
        }
    }
}
