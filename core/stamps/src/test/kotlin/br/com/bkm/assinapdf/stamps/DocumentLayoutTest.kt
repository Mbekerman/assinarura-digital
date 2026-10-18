package br.com.bkm.assinapdf.stamps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLayoutTest {
    private val a4 = PageGeometry(0, PdfBox.ofSize(595.28, 841.89))
    private val a1 = PageGeometry(1, PdfBox.ofSize(2383.94, 1683.78))
    private val layout = DocumentLayout(listOf(a4, a1))

    @Test
    fun `paginas empilhadas em tamanho real e centralizadas`() {
        assertEquals(2383.94, layout.width, 1e-6)
        val s0 = layout.slots[0]
        assertEquals((2383.94 - 595.28) / 2, s0.left, 1e-6)
        assertEquals(0.0, s0.top, 1e-9)
        assertEquals(841.89 + layout.gap, layout.slots[1].top, 1e-6)
    }

    @Test
    fun `ponto mapeia para a pagina certa e volta`() {
        val s1 = layout.slots[1]
        val hit = layout.pageAt(s1.left + 10, s1.top + 20)
        assertEquals(1, hit?.index)
        assertEquals(Pair(10.0, 20.0), layout.toPage(hit!!, s1.left + 10, s1.top + 20))
        assertNull(layout.pageAt(1.0, 1.0)) // margem ao lado da A4
    }

    @Test
    fun `ajustes de largura e pagina e pagina atual`() {
        val fw = layout.fitWidth(0, 1080.0, 1920.0)
        assertEquals(1080.0 / 595.28, fw.scale, 1e-9)
        assertEquals(0, layout.currentPage(fw, 1080.0, 1920.0))
        val fp = layout.fitPage(1, 1080.0, 1920.0)
        assertTrue(1683.78 * fp.scale <= 1920.0 + 1e-6)
        assertEquals(1, layout.currentPage(fp, 1080.0, 1920.0))
        val scrolled = layout.scrollTo(1, fw, 1080.0, 1920.0)
        assertEquals(1, layout.currentPage(scrolled, 1080.0, 1920.0))
    }

    @Test
    fun `texto do carimbo nao afirma assinatura antes de assinar`() {
        val p = StampContent.preview(null)
        assertTrue(p.label.contains("PENDENTE"))
        assertTrue(!p.label.contains("ASSINADO DIGITALMENTE POR"))
        val d = StampContent.draft("FULANO", "18/10/2026 10:00")
        assertTrue(d.label.contains("NÃO ASSINADO"))
        assertTrue(!d.showIcpLogo) // rascunho não leva o logo ICP-Brasil nem o "verifique"
        assertTrue(!d.footer.contains("validar"))
        val f = StampContent.final("FULANO DE TAL", "05191421604", "18/10/2026 10:00:00 -03:00")
        assertEquals("CPF: 051.914.216-04", f.cpfLine)
        assertEquals("Verifique em validar.iti.gov.br", f.footer)
        assertTrue(f.showIcpLogo)
        // sem CPF no certificado, a linha some (não inventa)
        assertEquals("", StampContent.final("FULANO", null, "x").cpfLine)
        assertEquals(StampVisual.LOGO_PIXELS_W * StampVisual.LOGO_PIXELS_H, StampVisual.icpLogoAlpha.size)
        // espaço selo→texto igual ao espaço fim do logo→linha da moldura
        val sealRight = StampVisual.SEAL_CX + StampVisual.SEAL_R + StampVisual.SEAL_STROKE / 2
        val borderInner = StampVisual.BORDER_RIGHT - StampVisual.BORDER_WIDTH / 2
        assertEquals(StampVisual.TEXT_LEFT - sealRight, borderInner - StampVisual.NAME_RIGHT, 0.05)
        // bloco de texto centrado na vertical: maiúsculas do nome (Helvetica-Bold 0,718) ao rodapé
        val lines = StampVisual.lines(StampContent.final("MOISES CAPLUM BEKERMAN", "05191421604", "x"))
        val nameSize = (StampVisual.NAME_RIGHT - StampVisual.TEXT_LEFT) / 14.98
        val top = lines.first().baseline - 0.718 * nameSize
        val center = (top + lines.last().baseline) / 2
        assertEquals((StampVisual.BORDER_TOP + StampVisual.BORDER_BOTTOM) / 2, center, 0.3)
    }

    @Test
    fun `texto seguro para WinAnsi preserva acentos`() {
        assertEquals("JOSÉ CONCEIÇÃO · ÃÕ", StampVisual.winAnsiSafe("JOSÉ CONCEIÇÃO · ÃÕ"))
        assertEquals("?ódz", StampVisual.winAnsiSafe("Łódź"))
        assertEquals("a-b", StampVisual.winAnsiSafe("a—b"))
    }
}
