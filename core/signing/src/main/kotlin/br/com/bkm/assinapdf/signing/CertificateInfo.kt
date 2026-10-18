package br.com.bkm.assinapdf.signing

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.x500.RDN
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.CertificatePolicies
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/** Dados do certificado usados no carimbo e nas verificações. Nada é inventado: o que falta fica nulo. */
data class CertificateInfo(
    val holderName: String,
    val cpf: String?,
    val issuerName: String?,
    val serialHex: String,
    val notBefore: Date,
    val notAfter: Date,
    val isIcpBrasil: Boolean,
    val keyAlgorithm: String,
) {
    fun isValidAt(date: Date): Boolean = !date.before(notBefore) && !date.after(notAfter)

    companion object {
        /** Prefixo das políticas de certificado da ICP-Brasil (2.16.76.1.2). */
        private const val ICP_POLICY_PREFIX = "2.16.76.1.2."

        /** OID do CPF do titular no otherName da ICP-Brasil (2.16.76.1.3.1). */
        private const val OID_PF_DATA = "2.16.76.1.3.1"

        fun from(cert: X509Certificate): CertificateInfo {
            val holder = X509CertificateHolder(cert.encoded)
            val subject = holder.subject
            val cn = firstValue(subject, BCStyle.CN) ?: subject.toString()
            // ICP-Brasil: "NOME DO TITULAR:12345678901" (CPF ou CNPJ após os dois-pontos)
            val (name, doc) = splitIcpCn(cn)
            val issuerCn = firstValue(holder.issuer, BCStyle.CN)
            val policies = runCatching {
                val ext = holder.getExtension(Extension.certificatePolicies) ?: return@runCatching emptyList<String>()
                CertificatePolicies.getInstance(ext.parsedValue).policyInformation.map { it.policyIdentifier.id }
            }.getOrDefault(emptyList())
            val icp = policies.any { it.startsWith(ICP_POLICY_PREFIX) } ||
                subject.toString().contains("ICP-Brasil", ignoreCase = true)
            return CertificateInfo(
                holderName = name,
                cpf = doc?.takeIf { it.length == 11 } ?: cpfFromSan(cert),
                issuerName = issuerCn,
                serialHex = cert.serialNumber.toString(16).uppercase(),
                notBefore = cert.notBefore,
                notAfter = cert.notAfter,
                isIcpBrasil = icp,
                keyAlgorithm = cert.publicKey.algorithm,
            )
        }

        internal fun splitIcpCn(cn: String): Pair<String, String?> {
            val idx = cn.lastIndexOf(':')
            if (idx > 0) {
                val tail = cn.substring(idx + 1).trim()
                if (tail.isNotEmpty() && tail.all { it.isDigit() }) return Pair(cn.substring(0, idx).trim(), tail)
            }
            return Pair(cn.trim(), null)
        }

        private fun firstValue(name: X500Name, oid: ASN1ObjectIdentifier): String? {
            val rdns: Array<RDN> = name.getRDNs(oid)
            return rdns.firstOrNull()?.first?.value?.toString()
        }

        /**
         * CPF no otherName 2.16.76.1.3.1 da ICP-Brasil: data de nascimento (8 dígitos) seguida do
         * CPF (11 dígitos) e de outros campos.
         */
        private fun cpfFromSan(cert: X509Certificate): String? = runCatching {
            cert.subjectAlternativeNames?.firstNotNullOfOrNull { entry ->
                val raw = entry.getOrNull(1) as? ByteArray
                if (entry[0] != 0 || raw == null) return@firstNotNullOfOrNull null
                val seq = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(raw))
                val oid = ASN1ObjectIdentifier.getInstance(seq.getObjectAt(0)).id
                if (oid != OID_PF_DATA) return@firstNotNullOfOrNull null
                var value: ASN1Primitive = seq.getObjectAt(1).toASN1Primitive()
                while (value is ASN1TaggedObject) value = value.baseObject.toASN1Primitive()
                val text = when (value) {
                    is ASN1OctetString -> String(value.octets, Charsets.ISO_8859_1)
                    is ASN1String -> value.string
                    else -> null
                }
                text?.takeIf { it.length >= 19 }?.substring(8, 19)?.takeIf { d -> d.all { it.isDigit() } && d.any { it != '0' } }
            }
        }.getOrNull()

        /** Lê um certificado em PEM ou em Base64 de DER. */
        fun parseCertificate(text: String): X509Certificate {
            val body = text.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "")
                .filterNot { it.isWhitespace() }
            val der = Base64.getMimeDecoder().decode(body)
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        /** Lê vários certificados concatenados em PEM, ou um único em Base64. */
        fun parseChain(text: String): List<X509Certificate> {
            val pems = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----").findAll(text).map { it.value }.toList()
            return if (pems.isNotEmpty()) pems.map { parseCertificate(it) } else listOf(parseCertificate(text))
        }
    }
}
