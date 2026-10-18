package br.com.bkm.assinapdf.stamps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGeometryTest {
    private fun mm(mm: Double) = mm / PageGeometry.MM_PER_PT

    @Test
    fun `detecta formatos A0 a A4 em retrato e paisagem`() {
        assertEquals(PaperFormat.A4, PageGeometry(0, PdfBox.ofSize(595.28, 841.89)).paperFormat)
        assertEquals(PaperFormat.A4, PageGeometry(0, PdfBox.ofSize(841.89, 595.28)).paperFormat)
        assertEquals(PaperFormat.A3, PageGeometry(0, PdfBox.ofSize(841.89, 1190.55)).paperFormat)
        assertEquals(PaperFormat.A2, PageGeometry(0, PdfBox.ofSize(1190.55, 1683.78)).paperFormat)
        assertEquals(PaperFormat.A1, PageGeometry(0, PdfBox.ofSize(1683.78, 2383.94)).paperFormat)
        assertEquals(PaperFormat.A0, PageGeometry(0, PdfBox.ofSize(2383.94, 3370.39)).paperFormat)
        assertEquals(PaperFormat.CUSTOM, PageGeometry(0, PdfBox.ofSize(mm(500.0), mm(700.0))).paperFormat)
    }

    @Test
    fun `tolerancia aceita decimos de milimetro mas nao formatos vizinhos`() {
        assertEquals(PaperFormat.A1, PageGeometry(0, PdfBox.ofSize(mm(596.0), mm(839.0))).paperFormat)
        assertEquals(PaperFormat.CUSTOM, PageGeometry(0, PdfBox.ofSize(mm(560.0), mm(841.0))).paperFormat)
    }

    @Test
    fun `rotacao troca largura e altura exibidas`() {
        val p = PageGeometry(0, PdfBox.ofSize(595.0, 842.0), rotation = 90)
        assertEquals(842.0, p.displayWidthPt, 1e-9)
        assertEquals(595.0, p.displayHeightPt, 1e-9)
        assertTrue(p.isLandscape)
        assertEquals(PaperFormat.A4, p.paperFormat)
    }

    @Test
    fun `cropbox deslocado define a area visivel`() {
        val p = PageGeometry(0, mediaBox = PdfBox(0.0, 0.0, 1000.0, 1000.0), cropBox = PdfBox(100.0, 200.0, 700.0, 900.0))
        assertEquals(600.0, p.displayWidthPt, 1e-9)
        assertEquals(700.0, p.displayHeightPt, 1e-9)
        // canto superior esquerdo da exibição = (llx, ury) do CropBox
        assertEquals(Pair(100.0, 900.0), p.displayToUser(0.0, 0.0))
    }

    @Test
    fun `userunit altera medidas fisicas mas nao coordenadas`() {
        val p = PageGeometry(0, PdfBox.ofSize(1000.0, 1000.0), userUnit = 2.0)
        assertEquals(1000.0 * 2 * PageGeometry.MM_PER_PT, p.widthMm, 1e-9)
        assertEquals(Pair(10.0, 990.0), p.displayToUser(10.0, 10.0))
    }

    @Test
    fun `displayToUser e userToDisplay sao inversas em todas as rotacoes`() {
        for (rot in listOf(0, 90, 180, 270)) {
            val p = PageGeometry(0, mediaBox = PdfBox(0.0, 0.0, 800.0, 600.0), cropBox = PdfBox(50.0, 20.0, 750.0, 580.0), rotation = rot)
            for ((xd, yd) in listOf(0.0 to 0.0, 123.4 to 56.7, p.displayWidthPt to p.displayHeightPt)) {
                val (xu, yu) = p.displayToUser(xd, yd)
                val (xd2, yd2) = p.userToDisplay(xu, yu)
                assertEquals("rot $rot", xd, xd2, 1e-9)
                assertEquals("rot $rot", yd, yd2, 1e-9)
            }
        }
    }

    @Test
    fun `cantos da exibicao correspondem aos cantos certos do espaco do usuario`() {
        val box = PdfBox(0.0, 0.0, 400.0, 600.0)
        // rotação 90 (horário): o canto inferior esquerdo do usuário vira o superior esquerdo da tela
        val p90 = PageGeometry(0, box, rotation = 90)
        assertEquals(Pair(0.0, 0.0), p90.displayToUser(0.0, 0.0))
        assertEquals(Pair(0.0, 600.0), p90.displayToUser(600.0, 0.0)) // superior direito da tela = superior esquerdo do usuário
        // rotação 180
        val p180 = PageGeometry(0, box, rotation = 180)
        assertEquals(Pair(400.0, 0.0), p180.displayToUser(0.0, 0.0))
        // rotação 270 (anti-horário): o canto superior direito do usuário vira o superior esquerdo da tela
        val p270 = PageGeometry(0, box, rotation = 270)
        assertEquals(Pair(400.0, 600.0), p270.displayToUser(0.0, 0.0))
        assertEquals(Pair(400.0, 0.0), p270.displayToUser(600.0, 0.0))
    }

    @Test
    fun `normaliza rotacoes fora do intervalo`() {
        assertEquals(90, PageGeometry.normalizeRotation(-270))
        assertEquals(270, PageGeometry.normalizeRotation(-90))
        assertEquals(0, PageGeometry.normalizeRotation(360))
        assertEquals(180, PageGeometry.normalizeRotation(540))
    }

    @Test
    fun `descricao inclui formato e dimensoes`() {
        val p = PageGeometry(0, PdfBox.ofSize(1683.78, 2383.94))
        assertEquals("A1 · 594 × 841 mm", p.describe())
        assertFalse(p.isLandscape)
    }
}
