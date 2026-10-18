package br.com.bkm.assinapdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.bkm.assinapdf.pdf.engine.PdfSigner
import br.com.bkm.assinapdf.pdf.engine.SignatureMetadata
import br.com.bkm.assinapdf.pdf.engine.StampStyle
import br.com.bkm.assinapdf.stamps.Stamp
import br.com.bkm.assinapdf.stamps.StampContent
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/**
 * Roda o motor de PDF com o pdfbox-android do aparelho (os testes JVM usam o Apache PDFBox, que
 * grava atualizações incrementais de outro jeito). Em PDF já assinado, o carimbo vira widget de
 * uma assinatura nova e precisa estar no /Annots da página regravada, senão não aparece.
 * O CMS é fictício: aqui só importa a estrutura do PDF.
 */
@RunWith(AndroidJUnit4::class)
class VisibleStampOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fakeCms: suspend (ByteArray) -> ByteArray = { ByteArray(64) { 1 } }

    @Test
    fun carimboEmPdfJaAssinadoFicaNaPagina() = runBlocking {
        val dir = File(context.cacheDir, "carimbo-visivel").also { it.mkdirs() }
        val base = TestDocuments.create(context, "base-assinatura.pdf", pages = 2)
        val first = File(dir, "primeira.pdf")
        PdfSigner.sign(base, first, SignatureMetadata("Primeira", Calendar.getInstance()), cmsForDigest = fakeCms)

        val out = File(dir, "segunda.pdf")
        val stamps = listOf(
            Stamp(pageIndex = 1, centerX = 0.5, centerY = 0.4, widthFraction = 0.2),
            Stamp(pageIndex = 0, centerX = 0.3, centerY = 0.7, widthFraction = 0.15, rotationDeg = 30.0),
        )
        PdfSigner.signWithStamps(
            input = first,
            output = out,
            stamps = stamps,
            content = StampContent.final("TESTE", "18/10/2026 16:00:00 -03:00", null),
            style = StampStyle(),
            metadata = SignatureMetadata("TESTE", Calendar.getInstance()),
            workFile = { n -> File(dir, "$n.tmp.pdf") },
            cmsForDigest = fakeCms,
        )

        PDDocument.load(out).use { doc ->
            assertEquals(3, doc.signatureDictionaries.size)
            for (pageIndex in listOf(0, 1)) {
                val visible = doc.getPage(pageIndex).annotations.filter { it.subtype == "Widget" && it.rectangle.width > 0f }
                assertEquals("widgets visíveis no /Annots da página ${pageIndex + 1}", 1, visible.size)
                assertTrue(visible.single().appearance?.normalAppearance?.appearanceStream != null)
            }
        }
    }
}
