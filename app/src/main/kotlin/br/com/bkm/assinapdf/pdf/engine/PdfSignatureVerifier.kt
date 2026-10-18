package br.com.bkm.assinapdf.pdf.engine

import br.com.bkm.assinapdf.signing.CmsVerifier
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/** Resultado da verificação de uma assinatura do PDF. */
data class PdfSignatureCheck(
    val index: Int,
    val signerName: String?,
    val subFilter: String?,
    val coversWholeFile: Boolean,
    val valid: Boolean,
    val signerSerialHex: String?,
    val problems: List<String>,
)

data class PdfVerification(
    val signatures: List<PdfSignatureCheck>,
) {
    val last: PdfSignatureCheck? get() = signatures.lastOrNull()

    /** Todas as assinaturas conferem e a última cobre o arquivo inteiro. */
    val allValid: Boolean get() = signatures.isNotEmpty() && signatures.all { it.valid } && last?.coversWholeFile == true
}

/**
 * Verificação local de integridade: para cada assinatura, confere o ByteRange e o CMS contra os
 * bytes do arquivo. Não valida a cadeia ICP-Brasil nem revogação (use o VALIDAR do ITI).
 */
object PdfSignatureVerifier {

    fun verify(file: File): PdfVerification {
        val length = file.length()
        val sigs: List<PDSignature> = PdfInspector.open(file).use { it.signatureDictionaries }
        val checks = sigs.mapIndexed { i, sig ->
            val problems = mutableListOf<String>()
            val range = sig.byteRange
            if (range == null || range.size != 4 || range[0] != 0) {
                return@mapIndexed PdfSignatureCheck(i, sig.name, sig.subFilter, false, false, null, listOf("ByteRange inválido"))
            }
            val end = range[2].toLong() + range[3].toLong()
            val coversWhole = end == length
            if (end > length) problems += "ByteRange ultrapassa o arquivo"
            val contents = file.inputStream().use { sig.getContents(it) }
            val result = CmsVerifier.verifyDetached(contents, sig.signDate?.time) { out -> writeRanges(file, range, out) }
            problems += result.problems
            PdfSignatureCheck(
                index = i,
                signerName = sig.name,
                subFilter = sig.subFilter,
                coversWholeFile = coversWhole,
                valid = result.valid && end <= length,
                signerSerialHex = result.signerSerialHex,
                problems = problems,
            )
        }
        return PdfVerification(checks)
    }

    private fun writeRanges(file: File, range: IntArray, out: OutputStream) {
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(64 * 1024)
            for (k in 0 until 2) {
                var pos = range[2 * k].toLong()
                var remaining = range[2 * k + 1].toLong()
                raf.seek(pos)
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                    pos += n
                }
            }
        }
    }
}
