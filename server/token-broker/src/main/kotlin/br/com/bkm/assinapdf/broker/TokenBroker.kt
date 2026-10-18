package br.com.bkm.assinapdf.broker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Configuração lida de variáveis de ambiente. Nada disso é versionado. */
data class BrokerConfig(
    /** URL completa do endpoint de token do PSC. */
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String,
    /** Chave enviada pelo app no cabeçalho X-AssinaPDF-Key. */
    val appKey: String,
    /** URIs de retorno aceitas (as mesmas cadastradas no painel do SafeID). */
    val allowedRedirects: Set<String>,
    val bind: String = "127.0.0.1",
    val port: Int = 8080,
    val maxRequestsPerMinute: Int = 20,
) {
    init {
        require(tokenUrl.startsWith("https://") || tokenUrl.startsWith("http://127.0.0.1") || tokenUrl.startsWith("http://localhost")) {
            "PSC_TOKEN_URL deve usar HTTPS"
        }
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) { "PSC_CLIENT_ID e PSC_CLIENT_SECRET são obrigatórios" }
        require(appKey.length >= 16) { "BROKER_KEY deve ter pelo menos 16 caracteres" }
        require(allowedRedirects.isNotEmpty()) { "ALLOWED_REDIRECT_URIS é obrigatório" }
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): BrokerConfig = BrokerConfig(
            tokenUrl = env["PSC_TOKEN_URL"].orEmpty(),
            clientId = env["PSC_CLIENT_ID"].orEmpty(),
            clientSecret = env["PSC_CLIENT_SECRET"].orEmpty(),
            appKey = env["BROKER_KEY"].orEmpty(),
            allowedRedirects = env["ALLOWED_REDIRECT_URIS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            bind = env["BIND"] ?: "127.0.0.1",
            port = env["PORT"]?.toIntOrNull() ?: 8080,
        )
    }
}

/**
 * POST /v1/token  {code, code_verifier, redirect_uri}  →  {access_token, token_type, expires_in, scope}
 * GET  /health
 *
 * Por padrão escuta só em 127.0.0.1: publique atrás de um proxy reverso com TLS (Caddy, nginx).
 * Não registra corpo de requisição nem de resposta (contêm códigos e tokens).
 */
class TokenBroker(private val config: BrokerConfig) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private lateinit var server: HttpServer

    val port: Int get() = server.address.port

    fun start(): TokenBroker {
        server = HttpServer.create(InetSocketAddress(config.bind, config.port), 32)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/health") { ex -> respond(ex, 200, """{"status":"ok"}""") }
        server.createContext("/v1/token") { ex -> handleToken(ex) }
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    private fun handleToken(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "POST") return respond(ex, 405, error("method_not_allowed"))
            val key = ex.requestHeaders.getFirst("X-AssinaPDF-Key").orEmpty()
            if (!MessageDigest.isEqual(key.toByteArray(), config.appKey.toByteArray())) return respond(ex, 401, error("unauthorized"))
            if (!allow(ex.remoteAddress.address.hostAddress)) return respond(ex, 429, error("too_many_requests"))
            val body = ex.requestBody.use { it.readNBytes(MAX_BODY + 1) }
            if (body.size > MAX_BODY) return respond(ex, 413, error("payload_too_large"))
            val req = runCatching { json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject }.getOrNull()
                ?: return respond(ex, 400, error("invalid_json"))
            val code = req.str("code")
            val verifier = req.str("code_verifier")
            val redirect = req.str("redirect_uri")
            if (code.isNullOrBlank() || verifier.isNullOrBlank() || redirect.isNullOrBlank()) return respond(ex, 400, error("missing_fields"))
            if (redirect !in config.allowedRedirects) return respond(ex, 400, error("redirect_not_allowed"))

            val form = mapOf(
                "grant_type" to "authorization_code",
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "code" to code,
                "redirect_uri" to redirect,
                "code_verifier" to verifier,
            ).entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
            val upstream = http.send(
                HttpRequest.newBuilder(URI.create(config.tokenUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (upstream.statusCode() !in 200..299) {
                val status = if (upstream.statusCode() in 400..499) 400 else 502
                return respond(ex, status, error("upstream_${upstream.statusCode()}"))
            }
            val tokenJson = runCatching { json.parseToJsonElement(upstream.body()).jsonObject }.getOrNull()
                ?: return respond(ex, 502, error("upstream_invalid"))
            // devolve só o necessário ao app
            val filtered = buildJsonObject {
                for (k in listOf("access_token", "token_type", "expires_in", "scope")) tokenJson[k]?.let { put(k, it) }
            }
            if (filtered["access_token"] == null) return respond(ex, 502, error("upstream_without_token"))
            respond(ex, 200, filtered.toString())
        } catch (e: Exception) {
            respond(ex, 502, error("broker_error"))
        }
    }

    private fun allow(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val q = hits.computeIfAbsent(ip) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= config.maxRequestsPerMinute) return false
            q.addLast(now)
            return true
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun error(code: String) = """{"error":"$code"}"""

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val MAX_BODY = 4096
    }
}

fun main() {
    val broker = TokenBroker(BrokerConfig.fromEnv()).start()
    println("token-broker escutando na porta ${broker.port}")
}
