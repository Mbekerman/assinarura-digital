package br.com.bkm.assinapdf.signing.testing

import br.com.bkm.assinapdf.signing.RemoteSigner
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.CertificatePolicies
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.PolicyInformation
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.DigestInfo
import org.bouncycastle.asn1.DERNull
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date

/**
 * ICP de TESTE gerada em memória (AC raiz → certificado do titular), usada apenas em testes
 * automatizados. Não tem relação com a ICP-Brasil; serve para provar que o CMS e o PDF são
 * montados e verificados corretamente.
 */
class TestPki(
    holderCn: String = "FULANO DE TAL:12345678901",
    notBefore: Date = Date(System.currentTimeMillis() - 86_400_000L),
    notAfter: Date = Date(System.currentTimeMillis() + 365L * 86_400_000L),
) {
    val caKeys: KeyPair = rsa()
    val holderKeys: KeyPair = rsa()
    val caCert: X509Certificate
    val holderCert: X509Certificate

    init {
        val caName = X500Name("CN=AC TESTE ASSINAPDF,O=Teste,C=BR")
        caCert = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(caName, BigInteger.ONE, notBefore, notAfter, caName, caKeys.public)
                .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
                .build(JcaContentSignerBuilder("SHA256withRSA").build(caKeys.private)),
        )
        // otherName ICP-Brasil 2.16.76.1.3.1: nascimento (ddmmaaaa) + CPF + NIS + RG + órgão
        val pfData = "01011980" + "12345678901" + "00000000000" + "000000000000000" + "0000000000"
        val otherName = ASN1EncodableVector().apply {
            add(ASN1ObjectIdentifier("2.16.76.1.3.1"))
            add(DERTaggedObject(true, 0, DEROctetString(pfData.toByteArray(Charsets.ISO_8859_1))))
        }
        holderCert = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                caName, BigInteger.valueOf(0x5AFE1D), notBefore, notAfter,
                X500Name("CN=$holderCn,OU=Teste,O=ICP-Brasil,C=BR"), holderKeys.public,
            )
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
                .addExtension(Extension.certificatePolicies, false, CertificatePolicies(PolicyInformation(ASN1ObjectIdentifier("2.16.76.1.2.3.99"))))
                .addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.otherName, DERSequence(otherName))))
                .build(JcaContentSignerBuilder("SHA256withRSA").build(caKeys.private)),
        )
    }

    /** Assina como um PSC faria no formato RAW: RSA PKCS#1 v1.5 sobre DigestInfo(SHA-256, hash). */
    fun rawSign(digest: ByteArray, key: PrivateKey = holderKeys.private): ByteArray {
        val digestInfo = DigestInfo(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE), digest).encoded
        return Signature.getInstance("NONEwithRSA").run {
            initSign(key)
            update(digestInfo)
            sign()
        }
    }

    fun signer(): RemoteSigner = object : RemoteSigner {
        override val certificate: X509Certificate = holderCert
        override val chain: List<X509Certificate> = listOf(holderCert, caCert)
        override suspend fun signDigest(digest: ByteArray): ByteArray = rawSign(digest)
    }

    fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"

    companion object {
        private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
