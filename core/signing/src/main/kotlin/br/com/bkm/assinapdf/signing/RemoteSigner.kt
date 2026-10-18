package br.com.bkm.assinapdf.signing

import java.security.cert.X509Certificate

/**
 * Quem assina o hash. Na produção é o PSC (SafeID); nos testes, uma chave local de teste.
 * A interface existe para manter o PDF e o CMS independentes do provedor.
 */
interface RemoteSigner {
    val certificate: X509Certificate
    val chain: List<X509Certificate>

    /** Assina o SHA-256 [digest] e devolve a assinatura RSA PKCS#1 v1.5. */
    suspend fun signDigest(digest: ByteArray): ByteArray
}

/** Assinante que usa o PSC com um token já autorizado. */
class PscRemoteSigner(
    private val client: PscApiClient,
    private val token: AccessToken,
    private val cert: CloudCertificate,
    private val label: String,
) : RemoteSigner {
    override val certificate: X509Certificate get() = cert.certificate
    override val chain: List<X509Certificate> get() = cert.chain

    override suspend fun signDigest(digest: ByteArray): ByteArray {
        if (!token.isValid()) throw SigningException.SessionExpired()
        val raw = client.signHash(token, cert.alias, digest, label)
        if (!PadesCms.verifyRawRsaSignature(cert.certificate, digest, raw)) {
            throw SigningException.InvalidResponse("a assinatura devolvida não confere com o certificado")
        }
        return raw
    }
}

/**
 * Produz o CMS PAdES para o hash do ByteRange: monta os atributos, pede a assinatura e confere.
 */
class PadesCmsSigner(
    private val signer: RemoteSigner,
    private val policy: SignaturePolicy? = null,
) {
    suspend fun sign(byteRangeDigest: ByteArray): ByteArray {
        val attrs = PadesCms.buildSignedAttributes(byteRangeDigest, signer.certificate, policy)
        val raw = signer.signDigest(attrs.digestToSign)
        if (!PadesCms.verifyRawRsaSignature(signer.certificate, attrs.digestToSign, raw)) {
            throw SigningException.Crypto("a assinatura recebida não confere com a chave do certificado")
        }
        return PadesCms.assemble(attrs, raw, signer.certificate, signer.chain)
    }
}
