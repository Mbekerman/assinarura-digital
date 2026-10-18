package br.com.bkm.assinapdf.verify

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/** Gera PDFs de teste com desenho vetorial (grade e texto), em tamanhos e orientações variados. */
object TestPdfs {
    private const val MM = 72.0 / 25.4

    data class Spec(val widthMm: Double, val heightMm: Double, val rotation: Int = 0, val cropInsetMm: Double = 0.0)

    val A4 = Spec(210.0, 297.0)
    val A3_LANDSCAPE = Spec(420.0, 297.0)
    val A2 = Spec(420.0, 594.0)
    val A1_LANDSCAPE = Spec(841.0, 594.0)
    val A0 = Spec(841.0, 1189.0)

    fun outDir(): File = File(System.getProperty("assinapdf.out") ?: "build/test-pdfs").also { it.mkdirs() }

    fun create(name: String, vararg specs: Spec): File {
        val file = File(outDir(), name)
        PDDocument().use { doc ->
            specs.forEachIndexed { i, s ->
                val w = (s.widthMm * MM).toFloat()
                val h = (s.heightMm * MM).toFloat()
                val page = PDPage(PDRectangle(w, h))
                page.rotation = s.rotation
                if (s.cropInsetMm > 0) {
                    val inset = (s.cropInsetMm * MM).toFloat()
                    page.cropBox = PDRectangle(inset, inset, w - 2 * inset, h - 2 * inset)
                }
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    // grade vetorial fina a cada 10 mm, como uma planta
                    cs.setStrokingColor(0.75f, 0.75f, 0.75f)
                    cs.setLineWidth(0.2f)
                    var x = 0f
                    while (x <= w) { cs.moveTo(x, 0f); cs.lineTo(x, h); x += (10 * MM).toFloat() }
                    var y = 0f
                    while (y <= h) { cs.moveTo(0f, y); cs.lineTo(w, y); y += (10 * MM).toFloat() }
                    cs.stroke()
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(40f, 40f)
                    cs.showText("PLANTA DE TESTE PAGINA ${i + 1}")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }
}
