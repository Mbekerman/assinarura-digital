package br.com.bkm.assinapdf.signing

import br.com.bkm.assinapdf.signing.testing.TestPki
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cms.CMSSignedData
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
