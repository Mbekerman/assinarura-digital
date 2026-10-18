package br.com.bkm.assinapdf.stamps

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class StampPlacementTest {
    private val a4 = PageGeometry(0, PdfBox.ofSize(595.28, 841.89))
    private val a1 = PageGeometry(1, PdfBox.ofSize(1683.78, 2383.94))
    private val a0 = PageGeometry(2, PdfBox.ofSize(2383.94, 3370.39))

    @Test
    fun `tamanho padrao e proporcional a largura da pagina`() {
        val f = StampDefaults.DEFAULT_WIDTH_FRACTION
        assertEquals(42.0, Stamp(pageIndex = 0, centerX = .5, centerY = .5, widthFraction = f).widthMm(a4), 0.1)
        assertEquals(118.8, Stamp(pageIndex = 1, centerX = .5, centerY = .5, widthFraction = f).widthMm(a1), 0.1)
        assertEquals(168.2, Stamp(pageIndex = 2, centerX = .5, centerY = .5, widthFraction = f).widthMm(a0), 0.1)
        // altura segue a proporção 3:1
        assertEquals(14.0, Stamp(pageIndex = 0, centerX = .5, centerY = .5, widthFraction = f).heightMm(a4), 0.1)
    }

    @Test
    fun `carimbo centralizado sem rotacao em pagina sem rotacao`() {
        val s = Stamp(pageIndex = 0, centerX = 0.5, centerY = 0.5, widthFraction = 0.2)
        val p = StampPlacement.place(s, a4)
        assertEquals(595.28 / 2, p.centerXUser, 1e-6)
        assertEquals(841.89 / 2, p.centerYUser, 1e-6)
        assertEquals(0.0, p.angleDegUser, 1e-9)
        // o centro do desenho cai no centro do carimbo
        val (cx, cy) = StampPlacement.apply(p.matrix, StampPlacement.DESIGN_WIDTH / 2, StampPlacement.DESIGN_HEIGHT / 2)
        assertEquals(p.centerXUser, cx, 1e-6)
        assertEquals(p.centerYUser, cy, 1e-6)
        // largura do desenho mapeada = largura em pt
        val (x0, _) = StampPlacement.apply(p.matrix, 0.0, 0.0)
        val (x1, _) = StampPlacement.apply(p.matrix, StampPlacement.DESIGN_WIDTH, 0.0)
        assertEquals(p.widthPt, x1 - x0, 1e-6)
    }

    @Test
    fun `canto superior esquerdo do desenho vai para o canto superior esquerdo visto na tela`() {
        // Sem rotação da página nem do carimbo: o canto (0, DESIGN_HEIGHT) do desenho (topo esquerdo em Y para cima)
        // deve ir para o canto superior esquerdo do carimbo no espaço do usuário.
        val s = Stamp(pageIndex = 0, centerX = 0.3, centerY = 0.2, widthFraction = 0.2)
        val p = StampPlacement.place(s, a4)
        val (tlx, tly) = StampPlacement.apply(p.matrix, 0.0, StampPlacement.DESIGN_HEIGHT)
        val expectedTopLeftDisplay = s.cornersPt(a4)[0]
        val (ex, ey) = a4.displayToUser(expectedTopLeftDisplay.first, expectedTopLeftDisplay.second)
        assertEquals(ex, tlx, 1e-6)
        assertEquals(ey, tly, 1e-6)
    }

    @Test
    fun `cantos do desenho coincidem com os cantos exibidos para todas as rotacoes de pagina e carimbo`() {
        val angles = listOf(0.0, 30.0, 45.0, 90.0, 180.0, 270.0, 333.0)
        for (rot in listOf(0, 90, 180, 270)) {
            val page = PageGeometry(0, mediaBox = PdfBox(0.0, 0.0, 800.0, 600.0), cropBox = PdfBox(40.0, 30.0, 760.0, 570.0), rotation = rot)
            for (angle in angles) {
                val s = Stamp(pageIndex = 0, centerX = 0.37, centerY = 0.61, widthFraction = 0.25, rotationDeg = angle)
                val p = StampPlacement.place(s, page)
                val corners = s.cornersPt(page) // TL, TR, BR, BL na tela
                val designCorners = listOf(
                    0.0 to StampPlacement.DESIGN_HEIGHT, // TL (Y para cima)
                    StampPlacement.DESIGN_WIDTH to StampPlacement.DESIGN_HEIGHT, // TR
                    StampPlacement.DESIGN_WIDTH to 0.0, // BR
                    0.0 to 0.0, // BL
                )
                for (i in 0 until 4) {
                    val (ux, uy) = StampPlacement.apply(p.matrix, designCorners[i].first, designCorners[i].second)
                    val (dx, dy) = page.userToDisplay(ux, uy)
                    assertEquals("rot=$rot angle=$angle corner=$i x", corners[i].first, dx, 1e-6)
                    assertEquals("rot=$rot angle=$angle corner=$i y", corners[i].second, dy, 1e-6)
                }
            }
        }
    }

    @Test
    fun `angulo PDF e R menos theta`() {
        val s90 = Stamp(pageIndex = 0, centerX = .5, centerY = .5, widthFraction = .2, rotationDeg = 90.0)
        assertEquals(270.0, StampPlacement.place(s90, a4).angleDegUser, 1e-9)
        val page90 = PageGeometry(0, PdfBox.ofSize(595.0, 842.0), rotation = 90)
        assertEquals(0.0, StampPlacement.place(s90, page90).angleDegUser, 1e-9)
        val s0 = s90.withRotation(0.0)
        assertEquals(90.0, StampPlacement.place(s0, page90).angleDegUser, 1e-9)
    }

    @Test
    fun `zoom do visualizador nao altera a colocacao`() {
        val s = Stamp(pageIndex = 0, centerX = 0.25, centerY = 0.75, widthFraction = 0.2, rotationDeg = 30.0)
        val p1 = StampPlacement.place(s, a4)
        // simula o carimbo passando por tela e voltando em outro zoom
        val v1 = Viewport.fitWidth(a4, 1080.0, 1920.0)
        val v2 = v1.zoomedAround(3.0, 500.0, 900.0, 0.1, 20.0)
        val (sx, sy) = v2.toScreen(s.centerPt(a4).first, s.centerPt(a4).second)
        val (xd, yd) = v2.toDisplay(sx, sy)
        val back = s.copy(centerX = xd / a4.displayWidthPt, centerY = yd / a4.displayHeightPt)
        val p2 = StampPlacement.place(back, a4)
        for (i in 0 until 6) assertEquals(p1.matrix[i], p2.matrix[i], 1e-6)
        assertEquals(p1.widthPt, p2.widthPt, 1e-9)
    }

    @Test
    fun `largura fisica e a mesma em qualquer rotacao`() {
        for (angle in listOf(0.0, 45.0, 90.0, 137.0)) {
            val s = Stamp(pageIndex = 1, centerX = .5, centerY = .5, widthFraction = .2, rotationDeg = angle)
            val p = StampPlacement.place(s, a1)
            val (x0, y0) = StampPlacement.apply(p.matrix, 0.0, 0.0)
            val (x1, y1) = StampPlacement.apply(p.matrix, StampPlacement.DESIGN_WIDTH, 0.0)
            assertEquals(p.widthPt, Math.hypot(x1 - x0, y1 - y0), 1e-6)
            assertEquals(118.8, a1.ptToMm(p.widthPt), 0.1)
        }
    }

    private fun assertClose(a: Double, b: Double) = assert(abs(a - b) < 1e-6) { "$a != $b" }
}
