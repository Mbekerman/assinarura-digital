package br.com.bkm.assinapdf.signing

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.DERIA5String
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber
import org.bouncycastle.asn1.cms.SignedData
import org.bouncycastle.asn1.cms.SignerIdentifier
import org.bouncycastle.asn1.cms.SignerInfo
import org.bouncycastle.asn1.esf.OtherHashAlgAndValue
import org.bouncycastle.asn1.esf.SigPolicyQualifierInfo
import org.bouncycastle.asn1.esf.SigPolicyQualifiers
import org.bouncycastle.asn1.esf.SignaturePolicyId
import org.bouncycastle.asn1.esf.SignaturePolicyIdentifier
import org.bouncycastle.asn1.ess.ESSCertIDv2
import org.bouncycastle.asn1.ess.SigningCertificateV2
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.DigestInfo
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.IssuerSerial
import org.bouncycastle.crypto.encodings.PKCS1Encoding
import org.bouncycastle.crypto.engines.RSABlindedEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Atributos assinados prontos: [digestToSign] é o que vai ao PSC. */
class SignedAttributes internal constructor(
    val set: ASN1Set,
    val der: ByteArray,
    val digestToSign: ByteArray,
)

/**
 * Montagem do contêiner CMS (SignedData destacado) para PAdES (subfiltro ETSI.CAdES.detached).
 *
 * Atributos assinados: content-type, message-digest (SHA-256 do ByteRange do PDF),
 * signing-certificate-v2 (ESS, obrigatório no PAdES baseline) e, opcionalmente, o identificador
 * da política de assinatura ICP-Brasil. O horário declarado da assinatura vai no /M do dicionário
 * de assinatura do PDF, como pede o PAdES baseline (sem atributo signing-time).
 */
object PadesCms {
    private val SHA256 = AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE)
    private val SHA256_WITH_RSA = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun buildSignedAttributes(
        contentDigest: ByteArray,
        signer: X509Certificate,
        policy: SignaturePolicy? = null,
    ): SignedAttributes {
        require(contentDigest.size == 32) { "message-digest deve ser SHA-256" }
        val v = ASN1EncodableVector()
        v.add(Attribute(CMSAttributes.contentType, DERSet(CMSObjectIdentifiers.data)))
        v.add(Attribute(CMSAttributes.messageDigest, DERSet(DEROctetString(contentDigest))))
        val certHash = sha256(signer.encoded)
        val issuerSerial = IssuerSerial(
            GeneralNames(GeneralName(X500Name.getInstance(signer.issuerX500Principal.encoded))),
            ASN1Integer(signer.serialNumber),
        )
        // SHA-256 é o padrão do ESSCertIDv2: em DER o algoritmo é omitido (o ITI alerta quando vem explícito)
        val essCertId = ESSCertIDv2(certHash, issuerSerial)
        v.add(Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, DERSet(SigningCertificateV2(arrayOf(essCertId)))))
        if (policy != null) {
            val hashAlg = AlgorithmIdentifier(ASN1ObjectIdentifier(policy.hashAlgorithmOid))
            val policyHash = OtherHashAlgAndValue(hashAlg, DEROctetString(Base64.getDecoder().decode(policy.hashBase64)))
            val qualifiers = policy.uri?.let {
                SigPolicyQualifiers(arrayOf(SigPolicyQualifierInfo(PKCSObjectIdentifiers.id_spq_ets_uri, DERIA5String(it))))
            }
            val spid = SignaturePolicyId(ASN1ObjectIdentifier(policy.oid), policyHash, qualifiers)
            v.add(Attribute(PKCSObjectIdentifiers.id_aa_ets_sigPolicyId, DERSet(SignaturePolicyIdentifier(spid))))
        }
        val set = DERSet(v) // DER ordena os elementos do SET, como exige a assinatura dos atributos
        val der = set.getEncoded(ASN1Encoding.DER)
        return SignedAttributes(set, der, sha256(der))
    }

    /**
     * Confere uma assinatura RSA PKCS#1 v1.5 recebida do PSC antes de embuti-la no PDF: decifra
     * com a chave pública do certificado e compara o DigestInfo com o hash enviado.
     */
    fun verifyRawRsaSignature(cert: X509Certificate, digest: ByteArray, signature: ByteArray): Boolean {
        val key = cert.publicKey as? RSAPublicKey ?: return false
        return try {
            val engine = PKCS1Encoding(RSABlindedEngine())
            engine.init(false, RSAKeyParameters(false, key.modulus, key.publicExponent))
            val decoded = engine.processBlock(signature, 0, signature.size)
            val expected = DigestInfo(SHA256, digest).getEncoded(ASN1Encoding.DER)
            MessageDigest.isEqual(decoded, expected)
        } catch (e: Exception) {
            false
        }
    }

    /** Monta o ContentInfo(SignedData) destacado com a assinatura bruta e a cadeia de certificados. */
    fun assemble(attrs: SignedAttributes, rawSignature: ByteArray, signer: X509Certificate, chain: List<X509Certificate>): ByteArray {
        if (signer.publicKey.algorithm != "RSA") {
            throw SigningException.Crypto("algoritmo de chave ${signer.publicKey.algorithm} não suportado (esperado RSA)")
        }
        val sid = SignerIdentifier(
            IssuerAndSerialNumber(X500Name.getInstance(signer.issuerX500Principal.encoded), signer.serialNumber),
        )
        val signerInfo = SignerInfo(sid, SHA256, attrs.set, SHA256_WITH_RSA, DEROctetString(rawSignature), null)
        val certs = ASN1EncodableVector()
        val all = (listOf(signer) + chain).distinctBy { it.serialNumber to it.issuerX500Principal.name }
        all.forEach { certs.add(Certificate.getInstance(ASN1Primitive.fromByteArray(it.encoded))) }
        val signedData = SignedData(
            DERSet(SHA256),
            ContentInfo(CMSObjectIdentifiers.data, null),
            DERSet(certs),
            null,
            DERSet(signerInfo as ASN1Encodable),
        )
        return ContentInfo(CMSObjectIdentifiers.signedData, signedData).getEncoded(ASN1Encoding.DER)
    }
}
