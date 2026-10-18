package br.com.bkm.assinapdf.pdf.engine

import br.com.bkm.assinapdf.stamps.PageGeometry
import br.com.bkm.assinapdf.stamps.Stamp
import br.com.bkm.assinapdf.stamps.StampContent
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSUpdateInfo
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Calendar

/** Carimbo desenhado como aparência da própria assinatura (widget do campo de assinatura). */
data class VisibleStamp(
    val stamp: Stamp,
    val content: StampContent,
    val style: StampStyle = StampStyle(),
)

/** Metadados do dicionário de assinatura. */
data class SignatureMetadata(
    val signerName: String,
    val signingTime: Calendar,
    val reason: String? = null,
    val location: String? = null,
)

/**
 * Aplica uma assinatura PAdES (subfiltro ETSI.CAdES.detached) em atualização incremental.
 *
 * Fluxo: reserva o espaço de /Contents, grava o arquivo com o ByteRange, calcula o SHA-256 dos
 * bytes cobertos, obtém o CMS de [cmsForDigest] e o escreve no espaço reservado. Nenhum byte do
 * documento é alterado depois disso.
 *
 * Com [VisibleStamp], o carimbo é a aparência do widget da nova assinatura. Em PDF já assinado,
 * é a forma de carimbo que o validador do ITI aceita sem rebaixar as assinaturas anteriores: sem
 * DocMDP declarado ele aplica o nível 2, que permite assinar mas não anotar. Campo e widget ficam
 * no mesmo dicionário (forma usual); um campo com vários widgets em /Kids não é reconhecido por
 * todos os leitores (o poppler, por exemplo, o mostra como não assinado).
 */
object PdfSigner {
    /** Espaço reservado para o CMS (bytes). Cadeia ICP-Brasil típica: 3 a 5 certificados. */
    const val RESERVED_CMS_BYTES = 32 * 1024

    suspend fun sign(
        input: File,
        output: File,
        metadata: SignatureMetadata,
        visible: VisibleStamp? = null,
        cmsForDigest: suspend (ByteArray) -> ByteArray,
    ) {
        require(input.canonicalPath != output.canonicalPath) { "a saída não pode sobrescrever a entrada" }
        PdfInspector.open(input).use { doc ->
            val info = PdfInspector.inspect(doc)
            info.signingBlocker()?.let { throw PdfOpenException(it) }

            val signature = PDSignature()
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
            signature.name = metadata.signerName
            signature.signDate = metadata.signingTime
            metadata.reason?.let { signature.reason = it }
            metadata.location?.let { signature.location = it }

            SignatureOptions().use { options ->
                options.preferredSignatureSize = RESERVED_CMS_BYTES
                if (visible != null) options.page = visible.stamp.pageIndex
                doc.addSignature(signature, options)
                // o pdfbox cria o widget como assinatura invisível (Rect zero, aparência vazia);
                // o carimbo passa a ser a aparência dele
                if (visible != null) applyStamp(doc, info.pages, signature, visible)
                // o pdfbox escreve o arquivo de entrada intacto e acrescenta a atualização incremental
                FileOutputStream(output).use { out ->
                    val external = doc.saveIncrementalForExternalSigning(out)
                    val md = MessageDigest.getInstance("SHA-256")
                    external.content.use { stream ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    val cms = cmsForDigest(md.digest())
                    if (cms.size > RESERVED_CMS_BYTES) {
                        throw IllegalStateException("assinatura maior que o espaço reservado (${cms.size} bytes)")
                    }
                    external.setSignature(cms)
                }
            }
        }
    }

    /**
     * Carimbos e assinatura, na forma que preserva as assinaturas existentes:
     * - PDF sem assinaturas: carimbos no conteúdo das páginas ([StampPdfWriter]) e uma assinatura;
     * - PDF já assinado: uma assinatura por carimbo, cada uma com o carimbo como aparência (uma
     *   anotação nova deixaria as anteriores "Indeterminadas" no validador do ITI).
     * [workFile] fornece arquivos intermediários, apagados ao final.
     */
    suspend fun signWithStamps(
        input: File,
        output: File,
        stamps: List<Stamp>,
        content: StampContent,
        style: StampStyle,
        metadata: SignatureMetadata,
        workFile: (String) -> File,
        onStep: (String) -> Unit = {},
        cmsForDigest: suspend (ByteArray) -> ByteArray,
    ) {
        val temps = ArrayList<File>()
        fun temp(name: String) = workFile(name).also { temps += it }
        try {
            when {
                !PdfInspector.inspect(input).hasSignatures -> {
                    onStep("Incorporando carimbos")
                    val stamped = temp("carimbado")
                    StampPdfWriter.write(input, stamped, stamps, content, style)
                    onStep("Aguardando assinatura no SafeID")
                    sign(stamped, output, metadata, cmsForDigest = cmsForDigest)
                }
                stamps.isEmpty() -> {
                    onStep("Aguardando assinatura no SafeID")
                    sign(input, output, metadata, cmsForDigest = cmsForDigest)
                }
                else -> {
                    var source = input
                    stamps.forEachIndexed { i, stamp ->
                        onStep(if (stamps.size == 1) "Aguardando assinatura no SafeID" else "Assinando carimbo ${i + 1} de ${stamps.size}")
                        val out = if (i == stamps.lastIndex) output else temp("etapa$i")
                        sign(source, out, metadata, VisibleStamp(stamp, content, style), cmsForDigest)
                        source = out
                    }
                    checkStampWidgets(output, stamps)
                }
            }
        } finally {
            temps.forEach { it.delete() }
        }
    }

    /** Confere, no arquivo gravado, que cada carimbo virou um widget visível na sua página. */
    private fun checkStampWidgets(file: File, stamps: List<Stamp>) {
        PdfInspector.open(file).use { doc ->
            for ((pageIndex, onPage) in stamps.groupBy { it.pageIndex }) {
                val visible = doc.getPage(pageIndex).annotations.count { a ->
                    a.subtype == "Widget" && a.rectangle != null && a.rectangle.width > 0f &&
                        (a.cosObject.getCOSName(COSName.FT) == COSName.SIG ||
                            a.cosObject.getCOSDictionary(COSName.PARENT)?.getCOSName(COSName.FT) == COSName.SIG)
                }
                if (visible < onPage.size) {
                    throw IllegalStateException("carimbo não incorporado à página ${pageIndex + 1}")
                }
            }
        }
    }

    private fun applyStamp(doc: PDDocument, pages: List<PageGeometry>, signature: PDSignature, visible: VisibleStamp) {
        val field = doc.signatureFields.first { it.cosObject.getCOSDictionary(COSName.V) === signature.cosObject }
        val widget = field.widgets.single()
        val form = StampPdfWriter.createForm(doc, visible.content, visible.style)
        val placed = StampPdfWriter.placedAppearance(doc, visible.stamp, pages[visible.stamp.pageIndex], form)
        widget.rectangle = placed.rect
        widget.appearance = PDAppearanceDictionary().also { it.setNormalAppearance(placed.appearance) }
        widget.cosObject.setInt(COSName.F, StampPdfWriter.FLAG_PRINT or StampPdfWriter.FLAG_LOCKED)
        markPathToRoot(doc, doc.getPage(visible.stamp.pageIndex).cosObject)
    }

    /**
     * Na gravação incremental, o pdfbox-android 2.0.27 parte da raiz e só desce por referências
     * marcadas, e confere a marca na referência indireta (COSObject), não no dicionário. Sem isso
     * a página com o novo /Annots (o widget do carimbo) nunca é regravada e o carimbo não aparece.
     * Marca a página, os nós acima dela, os /Kids e as referências do caminho até o /Pages do
     * catálogo. O Apache PDFBox 2.0.37 dos testes JVM grava mesmo sem isso.
     */
    private fun markPathToRoot(doc: PDDocument, page: COSDictionary) {
        page.setNeedToBeUpdated(true)
        (page.getItem(COSName.ANNOTS) as? COSUpdateInfo)?.setNeedToBeUpdated(true)
        (page.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.setNeedToBeUpdated(true)
        var child = page
        var parent = page.getCOSDictionary(COSName.PARENT)
        var guard = 0
        while (parent != null && guard++ < 64) {
            parent.setNeedToBeUpdated(true)
            (parent.getItem(COSName.KIDS) as? COSObject)?.setNeedToBeUpdated(true)
            val kids = parent.getDictionaryObject(COSName.KIDS) as? COSArray
            kids?.setNeedToBeUpdated(true)
            if (kids != null) {
                for (i in 0 until kids.size()) {
                    val ref = kids.get(i)
                    if (ref is COSObject && ref.getObject() === child) ref.setNeedToBeUpdated(true)
                }
            }
            child = parent
            parent = parent.getCOSDictionary(COSName.PARENT)
        }
        (doc.documentCatalog.cosObject.getItem(COSName.PAGES) as? COSObject)?.setNeedToBeUpdated(true)
    }
}
