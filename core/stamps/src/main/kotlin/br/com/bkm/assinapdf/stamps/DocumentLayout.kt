package br.com.bkm.assinapdf.stamps

import kotlin.math.max
import kotlin.math.min

/** Retângulo de uma página no espaço do documento contínuo (pt, Y para baixo). */
data class PageSlot(val index: Int, val left: Double, val top: Double, val width: Double, val height: Double) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
}

/**
 * Páginas empilhadas verticalmente, centralizadas na horizontal, em tamanho físico real:
 * uma folha A4 aparece menor que uma A1 no mesmo documento, como no papel.
 */
class DocumentLayout(val pages: List<PageGeometry>, gapFraction: Double = 0.02) {
    val width: Double = pages.maxOfOrNull { it.displayWidthPt } ?: 1.0
    val gap: Double = width * gapFraction
    val slots: List<PageSlot>
    val height: Double

    init {
        var y = 0.0
        val list = ArrayList<PageSlot>(pages.size)
        for (p in pages) {
            list += PageSlot(p.index, (width - p.displayWidthPt) / 2, y, p.displayWidthPt, p.displayHeightPt)
            y += p.displayHeightPt + gap
        }
        slots = list
        height = max(1.0, y - gap)
    }

    /** Página sob um ponto do documento, ou null se o ponto está fora de qualquer página. */
    fun pageAt(x: Double, y: Double): PageSlot? = slots.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }

    /** Converte um ponto do documento para o espaço de exibição da página [slot]. */
    fun toPage(slot: PageSlot, x: Double, y: Double): Pair<Double, Double> = Pair(x - slot.left, y - slot.top)

    fun fromPage(slot: PageSlot, xd: Double, yd: Double): Pair<Double, Double> = Pair(xd + slot.left, yd + slot.top)

    /** Páginas visíveis na janela da tela. */
    fun visible(viewport: Viewport, viewWidth: Double, viewHeight: Double): List<PageSlot> {
        val top = viewport.toDisplayY(0.0)
        val bottom = viewport.toDisplayY(viewHeight)
        val left = viewport.toDisplayX(0.0)
        val right = viewport.toDisplayX(viewWidth)
        return slots.filter { it.bottom >= top && it.top <= bottom && it.right >= left && it.left <= right }
    }

    /** Página atual: a que ocupa mais altura visível na tela. */
    fun currentPage(viewport: Viewport, viewWidth: Double, viewHeight: Double): Int {
        val top = viewport.toDisplayY(0.0)
        val bottom = viewport.toDisplayY(viewHeight)
        return slots.maxByOrNull { min(it.bottom, bottom) - max(it.top, top) }?.index ?: 0
    }

    /** Ajusta a largura da página [index] à tela e a posiciona no topo. */
    fun fitWidth(index: Int, viewWidth: Double, viewHeight: Double, paddingPx: Double = 0.0): Viewport {
        val slot = slots[index]
        val scale = max(1e-6, (viewWidth - 2 * paddingPx) / slot.width)
        val v = Viewport(scale, paddingPx - slot.left * scale, paddingPx - slot.top * scale)
        return clamp(v, viewWidth, viewHeight)
    }

    /** Mostra a página [index] inteira, centralizada. */
    fun fitPage(index: Int, viewWidth: Double, viewHeight: Double, paddingPx: Double = 0.0): Viewport {
        val slot = slots[index]
        val scale = max(1e-6, min((viewWidth - 2 * paddingPx) / slot.width, (viewHeight - 2 * paddingPx) / slot.height))
        val ox = (viewWidth - slot.width * scale) / 2 - slot.left * scale
        val oy = (viewHeight - slot.height * scale) / 2 - slot.top * scale
        return clamp(Viewport(scale, ox, oy), viewWidth, viewHeight)
    }

    /** Leva a página [index] para o topo da tela mantendo a escala. */
    fun scrollTo(index: Int, viewport: Viewport, viewWidth: Double, viewHeight: Double): Viewport {
        val slot = slots[index]
        return clamp(viewport.copy(offsetY = -slot.top * viewport.scale), viewWidth, viewHeight)
    }

    /**
     * Limita o deslocamento: o documento não pode sair da tela. Em cada eixo, se o documento é
     * menor que a tela ele fica centralizado; senão a borda não passa da borda da tela.
     */
    fun clamp(v: Viewport, viewWidth: Double, viewHeight: Double): Viewport {
        fun axis(offset: Double, content: Double, view: Double): Double =
            if (content <= view) (view - content) / 2 else offset.coerceIn(view - content, 0.0)
        return v.copy(offsetX = axis(v.offsetX, width * v.scale, viewWidth), offsetY = axis(v.offsetY, height * v.scale, viewHeight))
    }
}
