package br.com.bkm.assinapdf.signing

import android.net.Uri
import br.com.bkm.assinapdf.data.AppStorage
import br.com.bkm.assinapdf.data.SecureStore
import br.com.bkm.assinapdf.pdf.engine.PdfOpenException
import br.com.bkm.assinapdf.pdf.engine.PdfSignatureVerifier
import br.com.bkm.assinapdf.pdf.engine.PdfSigner
import br.com.bkm.assinapdf.pdf.engine.PdfVerification
import br.com.bkm.assinapdf.pdf.engine.SignatureMetadata
import br.com.bkm.assinapdf.pdf.engine.StampStyle
import br.com.bkm.assinapdf.sharing.FileNames
import br.com.bkm.assinapdf.stamps.Stamp
import br.com.bkm.assinapdf.stamps.StampContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Estado temporário da autorização em andamento (sobrevive ao encerramento do app). */
@Serializable
data class PendingAuthorization(val verifier: String, val challenge: String, val state: String, val documentId: String, val startedAtMs: Long)

@Serializable
private data class StoredToken(val value: String, val tokenType: String, val expiresAtEpochMs: Long, val scope: String? = null)

data class SignedOutcome(
    val file: File,
    val verification: PdfVerification,
    val holderName: String,
    val signingTimeText: String,
    val issuer: String?,
)

/**
 * Fluxo completo de assinatura com o SafeID:
 * autorização (navegador seguro) → token → certificado → carimbos finais → assinatura PAdES →
 * verificação local. Em qualquer falha, o documento original e os carimbos ficam intactos e
 * nenhum arquivo é marcado como assinado.
 */
class SigningService(
    private val storage: AppStorage,
    private val secure: SecureStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loaded = SafeIdSettings.load()

    val config: PscConfig? get() = loaded.config
    val configProblem: String? get() = loaded.problem
    val isConfigured: Boolean get() = loaded.config != null

    private val client: PscApiClient? by lazy { loaded.config?.let { PscApiClient(it) } }

    private fun requireClient(): PscApiClient = client ?: throw SigningException.NotConfigured(loaded.problem ?: "")

    // ---- Token ----

    fun cachedToken(): AccessToken? {
        val raw = secure.get(KEY_TOKEN) ?: return null
        val t = runCatching { json.decodeFromString(StoredToken.serializer(), raw) }.getOrNull() ?: return null
        val token = AccessToken(t.value, t.tokenType, t.expiresAtEpochMs, t.scope)
        return if (token.isValid()) token else null.also { secure.remove(KEY_TOKEN) }
    }

    private fun storeToken(token: AccessToken) {
        secure.put(KEY_TOKEN, json.encodeToString(StoredToken.serializer(), StoredToken(token.value, token.tokenType, token.expiresAtEpochMs, token.scope)))
    }

    fun clearToken() = secure.remove(KEY_TOKEN)

    // ---- Autorização ----

    /** Gera PKCE e state, guarda-os cifrados e devolve a URL a abrir no navegador seguro. */
    fun beginAuthorization(documentId: String, loginHint: String? = null): String {
        val c = requireClient()
        val pkce = Pkce.generate()
        val state = Pkce.randomState()
        val pending = PendingAuthorization(pkce.verifier, pkce.challenge, state, documentId, System.currentTimeMillis())
        secure.put(KEY_PENDING, json.encodeToString(PendingAuthorization.serializer(), pending))
        return c.authorizationUrl(pkce, state, loginHint)
    }

    fun pendingAuthorization(): PendingAuthorization? =
        secure.get(KEY_PENDING)?.let { runCatching { json.decodeFromString(PendingAuthorization.serializer(), it) }.getOrNull() }
            ?.takeIf { System.currentTimeMillis() - it.startedAtMs < 30 * 60_000L }

    fun cancelAuthorization() = secure.remove(KEY_PENDING)

    /** Processa o retorno do navegador: valida o state, troca o código e guarda o token. */
    suspend fun completeAuthorization(redirect: Uri): AccessToken {
        val c = requireClient()
        val pending = pendingAuthorization() ?: throw SigningException.InvalidResponse("nenhuma autorização em andamento")
        try {
            when (val result = c.parseRedirect(redirect.toString(), pending.state)) {
                is AuthorizationResult.Denied -> throw SigningException.Canceled()
                is AuthorizationResult.Code -> {
                    val token = c.exchangeCode(result.code, Pkce(pending.verifier, pending.challenge))
                    storeToken(token)
                    return token
                }
            }
        } finally {
            secure.remove(KEY_PENDING)
        }
    }

    suspend fun certificates(token: AccessToken): List<CloudCertificate> {
        val list = requireClient().discoverCertificates(token)
        if (list.isEmpty()) throw SigningException.CertificateUnavailable("Nenhum certificado disponível nesta conta SafeID.")
        return list
    }

    /**
     * Gera o PDF final: incorpora os carimbos com nome e horário reais, assina e verifica.
     * O arquivo só é entregue se a verificação local confirmar a integridade.
     */
    suspend fun signDocument(
        documentId: String,
        originalName: String,
        original: File,
        stamps: List<Stamp>,
        style: StampStyle,
        token: AccessToken,
        certificate: CloudCertificate,
        onStep: (String) -> Unit,
    ): SignedOutcome = withContext(Dispatchers.IO) {
        val c = requireClient()
        val info = certificate.info
        val now = Date()
        if (!info.isValidAt(now)) {
            throw SigningException.CertificateUnavailable(
                "O certificado de ${info.holderName} não está válido agora (validade até ${dateFormat().format(info.notAfter)}).",
            )
        }
        val calendar = Calendar.getInstance().apply { time = now }
        val timeText = timeFormat().format(now)
        val target = storage.uniqueOutput(documentId, FileNames.signedName(originalName))
        val partial = File(target.parentFile, target.name + ".partial")
        try {
            val cmsSigner = PadesCmsSigner(PscRemoteSigner(c, token, certificate, originalName), config?.signaturePolicy)
            try {
                PdfSigner.signWithStamps(
                    input = original,
                    output = partial,
                    stamps = stamps,
                    content = StampContent.final(info.holderName, info.cpf, timeText),
                    style = style,
                    metadata = SignatureMetadata(info.holderName, calendar, reason = "Assinatura digital ICP-Brasil"),
                    workFile = { name -> storage.tempFile(documentId, name) },
                    onStep = onStep,
                ) { digest -> cmsSigner.sign(digest) }
            } catch (e: PdfOpenException) {
                throw SigningException.UnsupportedDocument(e.message ?: "PDF não suportado")
            } catch (e: SigningException) {
                throw e
            } catch (e: Exception) {
                throw SigningException.Crypto(e.message ?: e.javaClass.simpleName, e)
            }
            onStep("Verificando a assinatura")
            val verification = PdfSignatureVerifier.verify(partial)
            val last = verification.last
            val sameSigner = last?.signerSerialHex.equals(info.serialHex, ignoreCase = true)
            if (!verification.allValid || !sameSigner) {
                val detail = last?.problems?.joinToString("; ").orEmpty().ifBlank { "o certificado do arquivo não é o autorizado" }
                throw SigningException.NotValidated(detail)
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = false)
            }
            SignedOutcome(target, verification, info.holderName, timeText, info.issuerName)
        } finally {
            partial.delete()
        }
    }

    companion object {
        private const val KEY_TOKEN = "safeid_token"
        private const val KEY_PENDING = "safeid_autorizacao"

        fun timeFormat() = SimpleDateFormat("dd/MM/yyyy HH:mm:ss XXX", Locale.forLanguageTag("pt-BR"))
        fun dateFormat() = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("pt-BR"))
    }
}
