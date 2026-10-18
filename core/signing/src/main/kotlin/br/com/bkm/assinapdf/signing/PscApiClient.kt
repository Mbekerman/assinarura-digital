package br.com.bkm.assinapdf.signing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Token de acesso devolvido pelo PSC. Nunca é registrado em log. */
data class AccessToken(
    val value: String,
    val tokenType: String,
    val expiresAtEpochMs: Long,
    val scope: String?,
) {
    fun isValid(nowMs: Long = System.currentTimeMillis(), marginMs: Long = 30_000): Boolean = nowMs + marginMs < expiresAtEpochMs
    override fun toString(): String = "AccessToken(type=$tokenType, expiresAt=$expiresAtEpochMs, scope=$scope)"
}

/** Certificado disponível para assinatura, com a cadeia quando o PSC a fornece. */
data class CloudCertificate(
    val alias: String,
    val certificate: X509Certificate,
    val chain: List<X509Certificate>,
) {
    val info: CertificateInfo by lazy { CertificateInfo.from(certificate) }
}

/** Resultado do redirecionamento da autorização. */
sealed class AuthorizationResult {
    data class Code(val code: String) : AuthorizationResult()
    data class Denied(val error: String, val description: String?) : AuthorizationResult()
}

/**
 * Cliente da API do PSC. Só faz chamadas HTTPS, nunca recebe ou envia o PIN e nunca envia o
 * documento: para assinar, envia apenas o hash SHA-256 dos atributos assinados.
 */
class PscApiClient(
    private val config: PscConfig,
    private val http: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** URL a abrir no navegador seguro (Custom Tab) para o titular autorizar o uso do certificado. */
    fun authorizationUrl(pkce: Pkce, state: String, loginHint: String? = null): String {
        val b = config.url(config.paths.authorize).toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("scope", config.scope)
            .addQueryParameter("state", state)
            .addQueryParameter("lifetime", config.lifetimeSeconds.toString())
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
        if (!loginHint.isNullOrBlank()) b.addQueryParameter("login_hint", loginHint)
        return b.build().toString()
    }

    /** Interpreta a URI de retorno; valida o state contra CSRF. */
    fun parseRedirect(redirect: String, expectedState: String): AuthorizationResult {
        val url = redirect.replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "https://").toHttpUrl()
        val state = url.queryParameter("state")
        val error = url.queryParameter("error")
        if (error != null) {
            return AuthorizationResult.Denied(error, url.queryParameter("error_description"))
        }
        if (state != expectedState) throw SigningException.InvalidResponse("parâmetro state não confere")
        val code = url.queryParameter("code") ?: throw SigningException.InvalidResponse("código de autorização ausente")
        return AuthorizationResult.Code(code)
    }

    /** Troca o código pelo token, direto no PSC (PKCE) ou via serviço intermediário. */
    suspend fun exchangeCode(code: String, pkce: Pkce): AccessToken = withContext(Dispatchers.IO) {
        val request = when (config.tokenMode) {
            TokenMode.DIRECT_PKCE -> Request.Builder()
                .url(config.url(config.paths.token))
                .post(
                    FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", config.clientId)
                        .add("code", code)
                        .add("redirect_uri", config.redirectUri)
                        .add("code_verifier", pkce.verifier)
                        .build(),
                )
                .build()
            TokenMode.BROKER -> {
                val body = buildJsonObject {
                    put("code", code)
                    put("code_verifier", pkce.verifier)
                    put("redirect_uri", config.redirectUri)
                }.toString()
                Request.Builder()
                    .url(config.brokerUrl!!.trimEnd('/') + "/v1/token")
                    .header("X-AssinaPDF-Key", config.brokerKey.orEmpty())
                    .post(body.toRequestBody(JSON))
                    .build()
            }
        }
        val obj = execute(request).jsonObject
        val token = obj.string("access_token") ?: throw SigningException.InvalidResponse("access_token ausente")
        val expiresIn = obj.string("expires_in")?.toLongOrNull() ?: config.lifetimeSeconds.toLong()
        AccessToken(token, obj.string("token_type") ?: "Bearer", clock() + expiresIn * 1000, obj.string("scope"))
    }

    /** Lista os certificados do titular autorizados para o token. */
    suspend fun discoverCertificates(token: AccessToken): List<CloudCertificate> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.url(config.paths.certificateDiscovery))
            .header("Authorization", "${token.tokenType} ${token.value}")
            .get()
            .build()
        val root = execute(request)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["certificates"] as? JsonArray ?: throw SigningException.InvalidResponse("lista de certificados ausente")
            else -> throw SigningException.InvalidResponse("formato inesperado na descoberta de certificados")
        }
        array.map { el ->
            val o = el.jsonObject
            val alias = o.string("alias") ?: throw SigningException.InvalidResponse("certificado sem alias")
            val certText = o.string("certificate") ?: throw SigningException.InvalidResponse("certificado ausente")
            val certs = runCatching { CertificateInfo.parseChain(certText) }
                .getOrElse { throw SigningException.InvalidResponse("certificado ilegível") }
            val extraChain = (o["certificate_chain"] ?: o["chain"])?.let { chainEl ->
                when (chainEl) {
                    is JsonArray -> chainEl.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { t -> runCatching { CertificateInfo.parseChain(t) }.getOrNull() } }.flatten()
                    is JsonPrimitive -> chainEl.contentOrNull?.let { runCatching { CertificateInfo.parseChain(it) }.getOrNull() } ?: emptyList()
                    else -> emptyList()
                }
            } ?: emptyList()
            val all = (certs + extraChain).distinctBy { it.serialNumber to it.issuerX500Principal.name }
            CloudCertificate(alias, all.first(), all)
        }
    }

    /**
     * Pede ao PSC a assinatura de um hash SHA-256. Com formato RAW, devolve a assinatura PKCS#1
     * v1.5; com CMS, o CMS destacado.
     */
    suspend fun signHash(token: AccessToken, alias: String, hash: ByteArray, label: String): ByteArray = withContext(Dispatchers.IO) {
        require(hash.size == 32) { "esperado hash SHA-256" }
        val body = buildJsonObject {
            put("certificate_alias", alias)
            put(
                "hashes",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "1")
                            put("alias", label)
                            put("hash", Base64.getEncoder().encodeToString(hash))
                            put(config.dialect.hashAlgorithmField, OID_SHA256)
                            put("signature_format", config.dialect.signatureFormat.wire)
                        },
                    )
                },
            )
        }.toString()
        val request = Request.Builder()
            .url(config.url(config.paths.signature))
            .header("Authorization", "${token.tokenType} ${token.value}")
            .post(body.toRequestBody(JSON))
            .build()
        val root = execute(request).jsonObject
        val signatures = root["signatures"] as? JsonArray ?: throw SigningException.InvalidResponse("campo signatures ausente")
        val first = signatures.firstOrNull()?.jsonObject ?: throw SigningException.InvalidResponse("nenhuma assinatura devolvida")
        val b64 = first.string("raw_signature") ?: first.string("signature")
            ?: throw SigningException.InvalidResponse("assinatura ausente na resposta")
        runCatching { Base64.getMimeDecoder().decode(b64) }.getOrElse { throw SigningException.InvalidResponse("assinatura não está em Base64") }
    }

    private fun execute(request: Request): JsonElement {
        val response: Response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw SigningException.Network(e)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw SigningException.SessionExpired()
            if (!r.isSuccessful) throw SigningException.Http(r.code, extractMessage(text))
            return runCatching { json.parseToJsonElement(text) }.getOrElse { throw SigningException.InvalidResponse("JSON inválido") }
        }
    }

    /** Extrai uma mensagem curta de erro, sem repetir o corpo inteiro (que pode conter dados pessoais). */
    private fun extractMessage(text: String): String = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        // o serviço intermediário devolve {error: "upstream_401", detail: "invalid_grant"}
        (o.string("error_description") ?: o.string("message")
            ?: listOfNotNull(o.string("error"), o.string("detail")).joinToString(" ").ifBlank { null })?.take(200)
    }.getOrNull().orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val OID_SHA256 = "2.16.840.1.101.3.4.2.1"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // a assinatura pode aguardar confirmação no celular
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
