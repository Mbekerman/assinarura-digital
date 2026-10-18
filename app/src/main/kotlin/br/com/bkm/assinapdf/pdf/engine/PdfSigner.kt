package br.com.bkm.assinapdf.pdf.engine

import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Calendar

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
 */
object PdfSigner {
    /** Espaço reservado para o CMS (bytes). Cadeia ICP-Brasil típica: 3 a 5 certificados. */
    const val RESERVED_CMS_BYTES = 32 * 1024

    suspend fun sign(
        input: File,
        output: File,
        metadata: SignatureMetadata,
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
                doc.addSignature(signature, options)
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
}
