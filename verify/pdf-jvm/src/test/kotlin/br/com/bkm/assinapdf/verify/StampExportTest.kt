package br.com.bkm.assinapdf.verify

import br.com.bkm.assinapdf.pdf.engine.PdfInspector
import br.com.bkm.assinapdf.pdf.engine.StampPdfWriter
import br.com.bkm.assinapdf.pdf.engine.StampStyle
import br.com.bkm.assinapdf.stamps.PageGeometry
import br.com.bkm.assinapdf.stamps.PaperFormat
import br.com.bkm.assinapdf.stamps.Stamp
import br.com.bkm.assinapdf.stamps.StampContent
import br.com.bkm.assinapdf.stamps.StampEditor
import br.com.bkm.assinapdf.stamps.StampPlacement
import br.com.bkm.assinapdf.stamps.StampVisual
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class StampExportTest {
    private val content = StampContent.draft("FULANO DE TAL", "18/10/2026 10:00")

    private fun export(input: File, name: String, stamps: List<Stamp>, style: StampStyle = StampStyle()): File {
        val out = File(TestPdfs.outDir(), name)
        StampPdfWriter.write(input, out, stamps, content, style)
        return out
    }

    @Test
    fun `B - formatos detectados e carimbo padrao com 20 por cento da largura em A4 a A0`() {
        val specs = listOf(TestPdfs.A4, TestPdfs.A3_LANDSCAPE, TestPdfs.A2, TestPdfs.A1_LANDSCAPE, TestPdfs.A0)
        val input = TestPdfs.create("formatos.pdf", *specs.toTypedArray())
        val info = PdfInspector.inspect(input)
        assertEquals(listOf(PaperFormat.A4, PaperFormat.A3, PaperFormat.A2, PaperFormat.A1, PaperFormat.A0), info.pages.map { it.paperFormat })
        val editor = StampEditor(info.pages)
        info.pages.forEach { editor.addAt(it.index, 0.5, 0.5) }
        val out = export(input, "formatos-carimbados.pdf", editor.sheet.stamps)
        PDDocument.load(out).use { doc ->
            for ((i, spec) in specs.withIndex()) {
                val m = FormProbe.probe(doc.getPage(i)).forms.single()
                val widthPt = hypot(m.scaleX.toDouble() * StampVisual.W, m.shearY.toDouble() * StampVisual.W)
                val widthMm = widthPt * 25.4 / 72
                assertEquals("página ${i + 1}", spec.widthMm * 0.20, widthMm, 0.05)
            }
        }
    }

    @Test
    fun `D - rotacoes exportadas coincidem com a previa (matriz e pixels)`() {
        val angles = listOf(0.0, 30.0, 45.0, 90.0, 180.0, 270.0)
        val input = TestPdfs.create("rotacoes.pdf", TestPdfs.A4, TestPdfs.A4.copy(rotation = 90), TestPdfs.A3_LANDSCAPE.copy(rotation = 270, cropInsetMm = 15.0))
        val pages = PdfInspector.inspect(input).pages
        val stamps = pages.flatMap { p ->
            angles.mapIndexed { k, a -> Stamp(pageIndex = p.index, centerX = 0.25 + 0.5 * (k % 2), centerY = 0.15 + 0.14 * k, widthFraction = 0.2, rotationDeg = a) }
        }
        val out = export(input, "rotacoes-carimbadas.pdf", stamps)
        PDDocument.load(out).use { doc ->
            val renderer = PDFRenderer(doc)
            for (page in pages) {
                val onPage = stamps.filter { it.pageIndex == page.index }
                val forms = FormProbe.probe(doc.getPage(page.index)).forms
                assertEquals(onPage.size, forms.size)
                for ((stamp, ctm) in onPage.zip(forms)) {
                    val expected = StampPlacement.place(stamp, page).matrix
                    val actual = doubleArrayOf(ctm.scaleX.toDouble(), ctm.shearY.toDouble(), ctm.shearX.toDouble(), ctm.scaleY.toDouble(), ctm.translateX.toDouble(), ctm.translateY.toDouble())
                    for (i in 0 until 6) assertEquals("página ${page.index} ângulo ${stamp.rotationDeg} m[$i]", expected[i], actual[i], 1e-2)
                    val angleUser = Math.toDegrees(atan2(actual[1], actual[0]))
                    val expectedAngle = Stamp.normalizeDegrees(page.rotation - stamp.rotationDeg)
                    assertEquals(0.0, angleDiff(Stamp.normalizeDegrees(angleUser), expectedAngle), 0.05)
                }
                // renderização: o anel do selo (azul) aparece onde a prévia o desenha
                val dpi = 144f
                val image = renderer.renderImageWithDPI(page.index, dpi)
                val pxPerPt = dpi / 72.0
                for (stamp in onPage) {
                    val ringTop = StampPlacement.designToDisplay(stamp, page, StampVisual.SEAL_CX, StampVisual.SEAL_CY - StampVisual.SEAL_R)
                    val ringLeft = StampPlacement.designToDisplay(stamp, page, StampVisual.SEAL_CX - StampVisual.SEAL_R, StampVisual.SEAL_CY)
                    for (pt in listOf(ringTop, ringLeft)) {
                        assertTrue(
                            "selo azul ausente em $pt (página ${page.index}, ângulo ${stamp.rotationDeg})",
                            hasColorNear(image, pt.first * pxPerPt, pt.second * pxPerPt, StampVisual.AZUL_BKM),
                        )
                    }
                    val border = StampPlacement.designToDisplay(stamp, page, StampVisual.W / 2, StampVisual.BORDER_BOTTOM)
                    assertTrue("moldura ausente", hasColorNear(image, border.first * pxPerPt, border.second * pxPerPt, StampVisual.MARINHO))
                }
            }
        }
    }

    @Test
    fun `E - dez carimbos independentes persistem no PDF`() {
        val input = TestPdfs.create("dez.pdf", TestPdfs.A1_LANDSCAPE, TestPdfs.A4)
        val pages = PdfInspector.inspect(input).pages
        val editor = StampEditor(pages)
        repeat(10) { i -> editor.addAt(i % 2, 0.08 + 0.09 * i, 0.1 + 0.08 * i) }
        val ids = editor.sheet.stamps.map { it.id }
        editor.setRotation(ids[3], 90.0)
        editor.setWidthFraction(ids[4], 0.1)
        val out = export(input, "dez-carimbados.pdf", editor.sheet.stamps)
        PDDocument.load(out).use { doc ->
            assertEquals(5, FormProbe.probe(doc.getPage(0)).forms.size)
            assertEquals(5, FormProbe.probe(doc.getPage(1)).forms.size)
            // o carimbo girado 90° sai como texto vertical (uma letra por linha); sem espaços, conta 10
            val text = PDFTextStripper().getText(doc).filterNot { it.isWhitespace() }
            assertEquals(10, Regex("RASCUNHO").findAll(text).count())
        }
    }

    @Test
    fun `F e G - paginas diferentes e zoom nao alteram geometria fisica`() {
        val input = TestPdfs.create("mistas.pdf", TestPdfs.A4, TestPdfs.A0.copy(rotation = 90), TestPdfs.A3_LANDSCAPE.copy(cropInsetMm = 20.0))
        val pages = PdfInspector.inspect(input).pages
        assertEquals(1189.0, pages[1].widthMm, 0.5) // A0 girada exibida em paisagem
        assertEquals(380.0, pages[2].widthMm, 0.5) // CropBox reduz a área visível
        val editor = StampEditor(pages)
        pages.forEach { editor.addAt(it.index, 0.3, 0.4) }
        val out = export(input, "mistas-carimbadas.pdf", editor.sheet.stamps)
        PDDocument.load(out).use { doc ->
            for (p in pages) {
                val m = FormProbe.probe(doc.getPage(p.index)).forms.single()
                val widthMm = hypot(m.scaleX.toDouble(), m.shearY.toDouble()) * StampVisual.W * 25.4 / 72
                assertEquals(p.widthMm * 0.2, widthMm, 0.05)
            }
        }
    }

    @Test
    fun `H - conteudo vetorial original preservado sem rasterizacao`() {
        val input = TestPdfs.create("planta.pdf", TestPdfs.A1_LANDSCAPE)
        val stamp = Stamp(pageIndex = 0, centerX = 0.85, centerY = 0.9, widthFraction = 0.12, rotationDeg = 0.0)
        val out = export(input, "planta-carimbada.pdf", listOf(stamp), StampStyle(whiteBackground = true))
        PDDocument.load(input).use { original ->
            PDDocument.load(out).use { stamped ->
                assertEquals(original.numberOfPages, stamped.numberOfPages)
                val o = original.getPage(0)
                val s = stamped.getPage(0)
                assertEquals(o.mediaBox.toString(), s.mediaBox.toString())
                assertEquals(0, FormProbe.probe(s).images)
                // o conteúdo original continua lá, intacto, antes do carimbo
                val originalOps = String(o.contents.readBytes(), Charsets.ISO_8859_1)
                val stampedOps = s.contentStreams.asSequence().map { String(it.createInputStream().readBytes(), Charsets.ISO_8859_1) }.joinToString("\n")
                assertTrue(stampedOps.contains(originalOps.trim()))
                assertTrue(PDFTextStripper().getText(stamped).contains("PLANTA DE TESTE PAGINA 1"))
            }
        }
    }

    @Test
    fun `nao sobrescreve o original`() {
        val input = TestPdfs.create("orig.pdf", TestPdfs.A4)
        try {
            StampPdfWriter.write(input, input, emptyList(), content)
            throw AssertionError("deveria recusar")
        } catch (e: IllegalArgumentException) { /* ok */ }
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    private fun hasColorNear(img: BufferedImage, x: Double, y: Double, argb: Int, radius: Int = 3, tolerance: Int = 60): Boolean {
        val tr = (argb shr 16) and 0xFF
        val tg = (argb shr 8) and 0xFF
        val tb = argb and 0xFF
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val px = (x + dx).toInt()
            val py = (y + dy).toInt()
            if (px !in 0 until img.width || py !in 0 until img.height) continue
            val c = img.getRGB(px, py)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (abs(r - tr) + abs(g - tg) + abs(b - tb) <= tolerance) return true
        }
        return false
    }

    @Suppress("unused")
    private fun PageGeometry.debug() = describe()
}
