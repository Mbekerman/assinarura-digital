package br.com.bkm.assinapdf.verify

import br.com.bkm.assinapdf.pdf.engine.PdfInspector
import br.com.bkm.assinapdf.pdf.engine.PdfSignatureVerifier
import br.com.bkm.assinapdf.pdf.engine.PdfSigner
import br.com.bkm.assinapdf.pdf.engine.SignatureMetadata
import br.com.bkm.assinapdf.pdf.engine.StampPdfWriter
import br.com.bkm.assinapdf.signing.CertificateInfo
import br.com.bkm.assinapdf.signing.CmsVerifier
import br.com.bkm.assinapdf.signing.PadesCmsSigner
import br.com.bkm.assinapdf.signing.testing.TestPki
import br.com.bkm.assinapdf.stamps.Stamp
import br.com.bkm.assinapdf.stamps.StampContent
import br.com.bkm.assinapdf.stamps.StampPlacement
import br.com.bkm.assinapdf.stamps.StampVisual
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class PdfSigningTest {
    private val pki = TestPki()
    private val holder = CertificateInfo.from(pki.holderCert)

    private suspend fun stampAndSign(input: File, name: String, stamps: List<Stamp>): File {
        val time = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo"))
        val stamped = File(TestPdfs.outDir(), "$name-carimbado.tmp.pdf")
        StampPdfWriter.write(input, stamped, stamps, StampContent.final(holder.holderName, "18/10/2026 10:00:00 -03:00", holder.issuerName))
        val signed = File(TestPdfs.outDir(), "$name-assinado.pdf")
        val cmsSigner = PadesCmsSigner(pki.signer())
        PdfSigner.sign(stamped, signed, SignatureMetadata(holder.holderName, time, reason = "Assinatura digital")) { digest -> cmsSigner.sign(digest) }
        return signed
    }

    @Test
    fun `I (local) - assinatura PAdES valida cobre o arquivo inteiro e preserva os carimbos`() = runTest {
        val input = TestPdfs.create("contrato.pdf", TestPdfs.A1_LANDSCAPE)
        val stamps = listOf(
            Stamp(pageIndex = 0, centerX = 0.2, centerY = 0.2, widthFraction = 0.2),
            Stamp(pageIndex = 0, centerX = 0.7, centerY = 0.5, widthFraction = 0.1, rotationDeg = 90.0),
            Stamp(pageIndex = 0, centerX = 0.5, centerY = 0.85, widthFraction = 0.15, rotationDeg = 33.0),
        )
        val signed = stampAndSign(input, "contrato", stamps)
        val stamped = File(TestPdfs.outDir(), "contrato-carimbado.tmp.pdf")
        // incremental: o arquivo assinado começa com os bytes exatos do carimbado
        val prefix = signed.readBytes().copyOf(stamped.length().toInt())
        assertArrayEquals(stamped.readBytes(), prefix)

        val v = PdfSignatureVerifier.verify(signed)
        assertEquals(1, v.signatures.size)
        assertTrue(v.last!!.problems.toString(), v.allValid)
        assertEquals("ETSI.CAdES.detached", v.last!!.subFilter)

        // verificação independente do nosso verificador: BouncyCastle via JCA sobre o ByteRange
        PDDocument.load(signed).use { doc ->
            val sig = doc.lastSignatureDictionary
            val content = sig.getSignedContent(signed.readBytes())
            // /Contents vem preenchido com zeros após o DER, como manda a especificação
            val cms = CMSSignedData(CMSProcessableByteArray(content), CmsVerifier.trimTrailingZeros(sig.getContents(signed.readBytes())))
            val signer = cms.signerInfos.signers.single()
            @Suppress("UNCHECKED_CAST")
            val cert = cms.certificates.getMatches(signer.sid as org.bouncycastle.util.Selector<X509CertificateHolder>).single() as X509CertificateHolder
            assertTrue(signer.verify(JcaSimpleSignerInfoVerifierBuilder().build(cert)))
            assertEquals(3, FormProbe.probe(doc.getPage(0)).forms.size)
            assertTrue(org.apache.pdfbox.text.PDFTextStripper().getText(doc).contains("ASSINADO DIGITALMENTE POR"))
        }
    }

    @Test
    fun `alterar um byte coberto invalida a assinatura`() = runTest {
        val input = TestPdfs.create("adulterar.pdf", TestPdfs.A4)
        val signed = stampAndSign(input, "adulterar", listOf(Stamp(pageIndex = 0, centerX = 0.5, centerY = 0.5, widthFraction = 0.2)))
        assertTrue(PdfSignatureVerifier.verify(signed).allValid)
        val bytes = signed.readBytes()
        // altera um byte dentro do primeiro trecho coberto pelo ByteRange (cabeçalho/primeiros objetos)
        val text = String(bytes, Charsets.ISO_8859_1)
        val idx = text.indexOf("/MediaBox").takeIf { it > 0 } ?: 20
        bytes[idx + 1] = 'm'.code.toByte()
        val tampered = File(TestPdfs.outDir(), "adulterado.pdf")
        tampered.writeBytes(bytes)
        val ok = runCatching { PdfSignatureVerifier.verify(tampered).allValid }.getOrDefault(false)
        assertFalse(ok)
    }

    @Test
    fun `PDF ja assinado - carimbos como anotacoes e assinatura anterior continua valida`() = runTest {
        val input = TestPdfs.create("ja-assinado.pdf", TestPdfs.A3_LANDSCAPE)
        val first = stampAndSign(input, "ja-assinado-1", emptyList())
        assertTrue(PdfInspector.inspect(first).hasSignatures)

        val stamp = Stamp(pageIndex = 0, centerX = 0.6, centerY = 0.4, widthFraction = 0.2, rotationDeg = 30.0)
        val second = stampAndSign(first, "ja-assinado-2", listOf(stamp))
        val v = PdfSignatureVerifier.verify(second)
        assertEquals(2, v.signatures.size)
        assertTrue(v.signatures.toString(), v.signatures.all { it.valid })
        assertFalse(v.signatures[0].coversWholeFile) // a primeira cobre só a revisão original, como esperado
        assertTrue(v.signatures[1].coversWholeFile)

        // a anotação aparece renderizada no lugar certo
        PDDocument.load(second).use { doc ->
            val page = PdfInspector.inspect(second).pages[0]
            val img = PDFRenderer(doc).renderImageWithDPI(0, 144f)
            val ring = StampPlacement.designToDisplay(stamp, page, StampVisual.SEAL_CX, StampVisual.SEAL_CY - StampVisual.SEAL_R)
            val px = (ring.first * 2).toInt()
            val py = (ring.second * 2).toInt()
            var found = false
            for (dx in -3..3) for (dy in -3..3) {
                val c = img.getRGB(px + dx, py + dy)
                if (((c shr 16) and 0xFF) < 80 && (c and 0xFF) > 200) found = true
            }
            assertTrue("carimbo em anotação não renderizado na posição esperada", found)
        }
    }
}
