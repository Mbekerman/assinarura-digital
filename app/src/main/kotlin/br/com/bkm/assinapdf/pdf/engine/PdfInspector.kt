package br.com.bkm.assinapdf.pdf.engine

import br.com.bkm.assinapdf.stamps.PageGeometry
import br.com.bkm.assinapdf.stamps.PdfBox
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File

/*
 * IMPORTANTE: os arquivos deste pacote (pdf/engine) não podem usar APIs Android.
 * O módulo verify/pdf-jvm compila cópias deles contra o Apache PDFBox para testá-los na JVM.
 */

/** Informações do PDF necessárias ao editor e às regras de assinatura. */
data class PdfInfo(
    val pages: List<PageGeometry>,
    val existingSignatures: Int,
    val encrypted: Boolean,
    val canModify: Boolean,
    val canModifyAnnotations: Boolean,
    /** Nível DocMDP de uma certificação existente (1, 2 ou 3), ou null se não for certificado. */
    val docMdpLevel: Int?,
) {
    val hasSignatures: Boolean get() = existingSignatures > 0

    /** Motivo pelo qual não é possível assinar sem invalidar algo, ou null se pode. */
    fun signingBlocker(): String? = when {
        !canModify && !canModifyAnnotations -> "O PDF está protegido contra alterações. Peça uma versão sem restrições."
        docMdpLevel == 1 -> "O PDF foi certificado sem permitir nenhuma alteração. Assinar invalidaria a certificação."
        else -> null
    }

    /**
     * Carimbos ao assinar PDF já assinado: vão como aparência da nova assinatura (widget), o que a
     * certificação DocMDP 2 permite; só a permissão de anotações do PDF protegido impede.
     */
    fun signatureStampBlocker(): String? = when {
        hasSignatures && !canModifyAnnotations -> "O PDF não permite anotações; só é possível assinar sem carimbo."
        else -> null
    }

    /** Aviso para carimbos como anotação (rascunho de PDF já assinado). */
    fun stampBlocker(): String? = when {
        docMdpLevel == 2 -> "A certificação deste PDF não permite carimbos visíveis. Só é possível assinar sem carimbo."
        hasSignatures && !canModifyAnnotations -> "O PDF não permite anotações; carimbos visíveis invalidariam as assinaturas existentes."
        else -> null
    }
}

class PdfOpenException(message: String, cause: Throwable? = null) : Exception(message, cause)

object PdfInspector {

    /** Abre o PDF com memória limitada (arquivo temporário em disco para PDFs grandes). */
    fun open(file: File): PDDocument = try {
        PDDocument.load(file, MemoryUsageSetting.setupMixed(32L * 1024 * 1024))
    } catch (e: InvalidPasswordException) {
        throw PdfOpenException("O PDF está protegido por senha.", e)
    } catch (e: Exception) {
        throw PdfOpenException("Não foi possível ler o PDF (arquivo corrompido ou não suportado).", e)
    }

    fun inspect(file: File): PdfInfo = open(file).use { doc -> inspect(doc) }

    fun inspect(doc: PDDocument): PdfInfo {
        val pages = doc.pages.mapIndexed { i, p -> geometryOf(i, p) }
        val sigs = runCatching { doc.signatureDictionaries.size }.getOrDefault(0)
        val ap = doc.currentAccessPermission
        return PdfInfo(
            pages = pages,
            existingSignatures = sigs,
            encrypted = doc.isEncrypted,
            canModify = ap.canModify(),
            canModifyAnnotations = ap.canModifyAnnotations(),
            docMdpLevel = docMdpLevel(doc),
        )
    }

    fun geometryOf(index: Int, page: PDPage): PageGeometry {
        val media = page.mediaBox.toBox()
        val crop = page.cropBox.toBox()
        val userUnit = runCatching { page.userUnit.toDouble() }.getOrDefault(1.0).takeIf { it > 0 } ?: 1.0
        return PageGeometry(
            index = index,
            mediaBox = media,
            cropBox = crop,
            rotation = PageGeometry.normalizeRotation(page.rotation),
            userUnit = userUnit,
        )
    }

    private fun PDRectangle.toBox() = PdfBox(lowerLeftX.toDouble(), lowerLeftY.toDouble(), upperRightX.toDouble(), upperRightY.toDouble()).normalized()

    /** Lê /Perms /DocMDP → /Reference[] → /TransformParams /P. */
    fun docMdpLevel(doc: PDDocument): Int? {
        val perms = doc.documentCatalog.cosObject.getDictionaryObject(COSName.PERMS) as? COSDictionary ?: return null
        val sig = perms.getDictionaryObject(COSName.DOCMDP) as? COSDictionary ?: return null
        val refs = sig.getDictionaryObject(COSName.getPDFName("Reference")) as? COSArray ?: return 2
        for (i in 0 until refs.size()) {
            val ref = refs.getObject(i) as? COSDictionary ?: continue
            val method = ref.getCOSName(COSName.getPDFName("TransformMethod"))
            if (method == COSName.DOCMDP) {
                val params = ref.getDictionaryObject(COSName.getPDFName("TransformParams")) as? COSDictionary
                return params?.getInt(COSName.P, 2)?.takeIf { it in 1..3 } ?: 2
            }
        }
        return 2
    }

    @Suppress("unused")
    private fun COSBase?.asDict(): COSDictionary? = this as? COSDictionary
}
