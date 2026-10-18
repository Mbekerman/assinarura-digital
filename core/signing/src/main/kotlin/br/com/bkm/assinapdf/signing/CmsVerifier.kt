package br.com.bkm.assinapdf.signing

import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSProcessable
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.DefaultCMSSignatureAlgorithmNameGenerator
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.bc.BcRSASignerInfoVerifierBuilder
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Date

/** Resultado da verificação local de um CMS destacado. */
data class CmsVerification(
    val valid: Boolean,
    val signerSerialHex: String?,
    val signerSubject: String?,
    val digestMatches: Boolean,
    val signatureValid: Boolean,
    val certValidAtSigning: Boolean?,
    val problems: List<String>,
)

/**
 * Verificação local da integridade criptográfica: o message-digest confere com o conteúdo
 * coberto pelo ByteRange e a assinatura dos atributos confere com a chave do certificado.
 * Não substitui a validação da cadeia ICP-Brasil e da revogação, que deve ser feita em
 * validadores como o VALIDAR do ITI.
 */
object CmsVerifier {

    /** [writeContent] escreve os bytes cobertos pelo ByteRange (pode ler de arquivo, sem carregar tudo em memória). */
    fun verifyDetached(cms: ByteArray, signingTime: Date?, writeContent: (OutputStream) -> Unit): CmsVerification {
        val problems = mutableListOf<String>()
        val processable = object : CMSProcessable {
            override fun write(out: OutputStream) = writeContent(out)
            override fun getContent(): Any? = null
        }
        val signed = try {
            CMSSignedData(processable, trimTrailingZeros(cms))
        } catch (e: Exception) {
            return CmsVerification(false, null, null, false, false, null, listOf("CMS ilegível: ${e.javaClass.simpleName}"))
        }
        val signers = signed.signerInfos.signers
        if (signers.size != 1) problems += "esperado 1 assinante, encontrados ${signers.size}"
        val signer: SignerInformation = signers.firstOrNull()
            ?: return CmsVerification(false, null, null, false, false, null, problems + "sem assinante")
        @Suppress("UNCHECKED_CAST")
        val holder = (signed.certificates.getMatches(signer.sid as org.bouncycastle.util.Selector<X509CertificateHolder>) as Collection<X509CertificateHolder>).firstOrNull()
            ?: return CmsVerification(false, null, null, false, false, null, problems + "certificado do assinante ausente no CMS")

        // message-digest x conteúdo
        val md = MessageDigest.getInstance("SHA-256")
        val digestOut = object : OutputStream() {
            override fun write(b: Int) = md.update(b.toByte())
            override fun write(b: ByteArray, off: Int, len: Int) = md.update(b, off, len)
        }
        writeContent(digestOut)
        val actual = md.digest()
        val attr = signer.signedAttributes?.get(CMSAttributes.messageDigest)
        val declared = attr?.attrValues?.getObjectAt(0)?.let { ASN1OctetString.getInstance(it).octets }
        val digestMatches = declared != null && MessageDigest.isEqual(actual, declared)
        if (!digestMatches) problems += "o hash do documento não confere com o declarado na assinatura"

        val signatureValid = try {
            val verifier = BcRSASignerInfoVerifierBuilder(
                DefaultCMSSignatureAlgorithmNameGenerator(),
                DefaultSignatureAlgorithmIdentifierFinder(),
                DefaultDigestAlgorithmIdentifierFinder(),
                BcDigestCalculatorProvider(),
            ).build(holder)
            signer.verify(verifier)
        } catch (e: Exception) {
            problems += "falha ao verificar a assinatura: ${e.javaClass.simpleName}"
            false
        }
        if (!signatureValid && problems.none { it.startsWith("falha") }) problems += "a assinatura não confere com o certificado"

        val certValid = signingTime?.let { holder.isValidOn(it) }
        if (certValid == false) problems += "o certificado não era válido no horário da assinatura"

        return CmsVerification(
            valid = digestMatches && signatureValid && certValid != false && signers.size == 1,
            signerSerialHex = holder.serialNumber.toString(16).uppercase(),
            signerSubject = holder.subject.toString(),
            digestMatches = digestMatches,
            signatureValid = signatureValid,
            certValidAtSigning = certValid,
            problems = problems,
        )
    }

    /** O /Contents do PDF é preenchido com zeros após o CMS; o DER tem tamanho próprio, então os zeros finais são descartáveis. */
    fun trimTrailingZeros(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x30.toByte()) return bytes
        // calcula o tamanho DER do SEQUENCE externo
        val lenByte = bytes[1].toInt() and 0xFF
        val total = if (lenByte < 0x80) {
            2 + lenByte
        } else {
            val n = lenByte and 0x7F
            var len = 0
            for (i in 0 until n) len = (len shl 8) or (bytes[2 + i].toInt() and 0xFF)
            2 + n + len
        }
        return if (total in 1..bytes.size) bytes.copyOf(total) else bytes
    }
}
