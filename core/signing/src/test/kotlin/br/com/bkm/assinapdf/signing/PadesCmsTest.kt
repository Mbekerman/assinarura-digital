package br.com.bkm.assinapdf.signing

import br.com.bkm.assinapdf.signing.testing.TestPki
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cms.CMSSignedData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Date

class PadesCmsTest {
    private val pki = TestPki()
    private val content = "%PDF-1.7 conteúdo coberto pelo ByteRange".toByteArray()

    @Test
    fun `cms destacado e verificavel e tem os atributos PAdES`() = runTest {
        val cms = PadesCmsSigner(pki.signer()).sign(PadesCms.sha256(content))
        val result = CmsVerifier.verifyDetached(cms, Date()) { it.write(content) }
        assertTrue(result.problems.toString(), result.valid)
        val signed = CMSSignedData(cms)
        val signer = signed.signerInfos.signers.single()
        val attrs = signer.signedAttributes
        assertNotNull(attrs.get(CMSAttributes.contentType))
        assertNotNull(attrs.get(CMSAttributes.messageDigest))
        assertNotNull(attrs.get(PKCSObjectIdentifiers.id_aa_signingCertificateV2))
        assertNull("PAdES baseline não usa signing-time", attrs.get(CMSAttributes.signingTime))
        assertNull(signed.signedContent) // destacado
        assertEquals(2, signed.certificates.getMatches(null).size)
        assertEquals("1.2.840.113549.1.1.11", signer.encryptionAlgOID)
    }

    @Test
    fun `conteudo alterado invalida`() = runTest {
        val cms = PadesCmsSigner(pki.signer()).sign(PadesCms.sha256(content))
        val tampered = content.copyOf().also { it[3] = 'X'.code.toByte() }
        val result = CmsVerifier.verifyDetached(cms, Date()) { it.write(tampered) }
        assertFalse(result.valid)
        assertFalse(result.digestMatches)
    }

    @Test
    fun `assinatura de outra chave e recusada antes de embutir`() = runTest {
        val other = TestPki()
        val impostor = object : RemoteSigner {
            override val certificate = pki.holderCert
            override val chain = listOf(pki.holderCert)
            override suspend fun signDigest(digest: ByteArray) = other.rawSign(digest)
        }
        try {
            PadesCmsSigner(impostor).sign(PadesCms.sha256(content))
            throw AssertionError("deveria recusar")
        } catch (e: SigningException.Crypto) { /* ok */ }
    }

    @Test
    fun `certificado vencido no horario da assinatura e apontado`() = runTest {
        val expired = TestPki(notBefore = Date(0), notAfter = Date(1_000_000L))
        val cms = PadesCmsSigner(expired.signer()).sign(PadesCms.sha256(content))
        val result = CmsVerifier.verifyDetached(cms, Date()) { it.write(content) }
        assertFalse(result.valid)
        assertEquals(false, result.certValidAtSigning)
    }

    @Test
    fun `politica de assinatura opcional e incluida quando configurada`() = runTest {
        val policy = SignaturePolicy("2.16.76.1.7.1.11.1.1", "2.16.840.1.101.3.4.2.1", Base64.getEncoder().encodeToString(ByteArray(32)), "http://exemplo/politica.der")
        val cms = PadesCmsSigner(pki.signer(), policy).sign(PadesCms.sha256(content))
        val attrs = CMSSignedData(cms).signerInfos.signers.single().signedAttributes
        assertNotNull(attrs.get(PKCSObjectIdentifiers.id_aa_ets_sigPolicyId))
        assertTrue(CmsVerifier.verifyDetached(cms, Date()) { it.write(content) }.valid)
    }

    @Test
    fun `sigPolicyId identico ao de uma assinatura AD-RT aprovada pelo ITI`() {
        // atributo extraído de uma assinatura PAdES AD-RT v1.3 real (outro sistema), aprovada no validar.iti.gov.br
        val aprovado = "30819A060B2A864886F70D010910020F31818A3081870608604C0107010C0103302F300B0609608648016503040201" +
            "042021D6E8D9F85E5626DC71307337A7CABD6FFEF8D085038746775CF0CE6BA4D3B5304A3048060B2A864886F70D010910050116" +
            "39687474703A2F2F706F6C6974696361732E69637062726173696C2E676F762E62722F50415F50416445535F41445F52545F76315F332E646572"
        val adRt = SignaturePolicy(
            oid = "2.16.76.1.7.1.12.1.3",
            hashAlgorithmOid = "2.16.840.1.101.3.4.2.1",
            hashBase64 = Base64.getEncoder().encodeToString(hex("21D6E8D9F85E5626DC71307337A7CABD6FFEF8D085038746775CF0CE6BA4D3B5")),
            uri = "http://politicas.icpbrasil.gov.br/PA_PAdES_AD_RT_v1_3.der",
        )
        val set = PadesCms.buildSignedAttributes(ByteArray(32), pki.holderCert, adRt).set
        val attr = set.map { org.bouncycastle.asn1.cms.Attribute.getInstance(it) }
            .single { it.attrType == PKCSObjectIdentifiers.id_aa_ets_sigPolicyId }
        assertArrayEquals(hex(aprovado), attr.getEncoded("DER"))
    }

    @Test
    fun `signing-certificate-v2 omite o algoritmo padrao SHA-256`() {
        val set = PadesCms.buildSignedAttributes(ByteArray(32), pki.holderCert).set
        val attr = set.map { org.bouncycastle.asn1.cms.Attribute.getInstance(it) }
            .single { it.attrType == PKCSObjectIdentifiers.id_aa_signingCertificateV2 }
        val certId = org.bouncycastle.asn1.ess.SigningCertificateV2.getInstance(attr.attrValues.getObjectAt(0)).certs.single()
        val seq = certId.toASN1Primitive() as org.bouncycastle.asn1.ASN1Sequence
        assertTrue(seq.toArray().none { it is org.bouncycastle.asn1.x509.AlgorithmIdentifier || (it is org.bouncycastle.asn1.ASN1Sequence && it.size() > 0 && it.getObjectAt(0) is org.bouncycastle.asn1.ASN1ObjectIdentifier) })
    }

    @Test
    fun `politica AD-RB v1_3 com os valores oficiais`() {
        val p = SignaturePolicy.PADES_AD_RB_V1_3
        assertEquals("2.16.76.1.7.1.11.1.3", p.oid)
        assertEquals("23E4BE4B9B362172E4EBB0E72B86A133ECE5AAD843D8651C6E38A0BA3F08FC60", Base64.getDecoder().decode(p.hashBase64).joinToString("") { "%02X".format(it) })
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `zeros de preenchimento do Contents sao descartados`() = runTest {
        val cms = PadesCmsSigner(pki.signer()).sign(PadesCms.sha256(content))
        val padded = cms + ByteArray(5000)
        assertEquals(cms.size, CmsVerifier.trimTrailingZeros(padded).size)
        assertTrue(CmsVerifier.verifyDetached(padded, Date()) { it.write(content) }.valid)
    }

    @Test
    fun `dados do certificado ICP-Brasil`() {
        val info = CertificateInfo.from(pki.holderCert)
        assertEquals("FULANO DE TAL", info.holderName)
        assertEquals("12345678901", info.cpf)
        assertEquals("AC TESTE ASSINAPDF", info.issuerName)
        assertTrue(info.isIcpBrasil)
        assertEquals(Pair("EMPRESA X LTDA", "12345678000199"), CertificateInfo.splitIcpCn("EMPRESA X LTDA:12345678000199"))
        assertEquals(Pair("SEM DOCUMENTO", null), CertificateInfo.splitIcpCn("SEM DOCUMENTO"))
        assertEquals("1.2.840.113549.1.1.11", ASN1ObjectIdentifier("1.2.840.113549.1.1.11").id)
    }
}
