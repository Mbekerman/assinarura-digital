package br.com.bkm.assinapdf.signing

import br.com.bkm.assinapdf.signing.testing.TestPki
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class PscApiClientTest {
    private lateinit var server: MockWebServer
    private val pki = TestPki()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** O MockWebServer é HTTP; o cliente exige HTTPS na configuração, então reescrevemos a URL na fábrica de teste. */
    private fun client(mode: TokenMode = TokenMode.DIRECT_PKCE): PscApiClient {
        val base = server.url("/").toString()
        val cfg = PscConfig(
            baseUrl = "https://placeholder/api/v0/oauth",
            clientId = "cliente-teste",
            tokenMode = mode,
            brokerUrl = if (mode == TokenMode.BROKER) "https://placeholder-broker/" else null,
            brokerKey = "k",
        )
        val http = PscApiClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val req = chain.request()
            val url = req.url.toString().replace("https://placeholder-broker/", base).replace("https://placeholder/", base)
            chain.proceed(req.newBuilder().url(url).build())
        }.build()
        return PscApiClient(cfg, http) { 1_000_000L }
    }

    @Test
    fun `pkce segue o exemplo da RFC 7636`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val p = Pkce.generate()
        assertTrue(p.verifier.length in 43..128)
    }

    @Test
    fun `url de autorizacao tem os parametros do fluxo com PKCE`() {
        val c = client()
        val url = c.authorizationUrl(Pkce("v", "desafio"), "estado123", loginHint = "12345678901").toHttpUrl()
        assertEquals("/api/v0/oauth/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("cliente-teste", url.queryParameter("client_id"))
        assertEquals("assinapdf://oauth/callback", url.queryParameter("redirect_uri"))
        assertEquals("signature_session", url.queryParameter("scope"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("desafio", url.queryParameter("code_challenge"))
        assertEquals("estado123", url.queryParameter("state"))
        assertEquals("12345678901", url.queryParameter("login_hint"))
    }

    @Test
    fun `redirect valida state e trata recusa`() {
        val c = client()
        assertEquals(AuthorizationResult.Code("abc"), c.parseRedirect("assinapdf://oauth/callback?code=abc&state=s1", "s1"))
        val denied = c.parseRedirect("assinapdf://oauth/callback?error=access_denied&state=s1", "s1")
        assertTrue(denied is AuthorizationResult.Denied)
        try {
            c.parseRedirect("assinapdf://oauth/callback?code=abc&state=outro", "s1")
            fail("deveria recusar state diferente")
        } catch (e: SigningException.InvalidResponse) { /* ok */ }
    }

    @Test
    fun `troca direta do codigo envia code_verifier e nunca client_secret`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"tok","token_type":"Bearer","expires_in":600,"scope":"signature_session"}"""))
        val token = client().exchangeCode("cod", Pkce("verificador", "x"))
        assertEquals("tok", token.value)
        assertEquals(1_000_000L + 600_000L, token.expiresAtEpochMs)
        val req = server.takeRequest()
        assertEquals("/api/v0/oauth/token", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("code_verifier=verificador"))
        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(!body.contains("client_secret"))
        assertTrue(!token.toString().contains("tok\""))
    }

    @Test
    fun `troca via servico intermediario`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"tok2","expires_in":"300"}"""))
        val token = client(TokenMode.BROKER).exchangeCode("cod", Pkce("ver", "x"))
        assertEquals("tok2", token.value)
        val req = server.takeRequest()
        assertEquals("/v1/token", req.path)
        assertEquals("k", req.getHeader("X-AssinaPDF-Key"))
        val json = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("ver", json["code_verifier"]!!.jsonPrimitive.content)
    }

    @Test
    fun `descoberta le certificado em PEM e cadeia`() = runTest {
        val pem = pki.pem(pki.holderCert).replace("\n", "\\n")
        val ca = pki.pem(pki.caCert).replace("\n", "\\n")
        server.enqueue(MockResponse().setBody("""{"status":"S","certificates":[{"alias":"meu-cert","certificate":"$pem","certificate_chain":["$ca"]}]}"""))
        val certs = client().discoverCertificates(AccessToken("t", "Bearer", Long.MAX_VALUE, null))
        assertEquals(1, certs.size)
        assertEquals("meu-cert", certs[0].alias)
        assertEquals(2, certs[0].chain.size)
        assertEquals("FULANO DE TAL", certs[0].info.holderName)
        val req = server.takeRequest()
        assertEquals("/api/v0/oauth/certificate-discovery", req.path)
        assertEquals("Bearer t", req.getHeader("Authorization"))
    }

    @Test
    fun `assinatura envia apenas o hash e decodifica a resposta`() = runTest {
        val hash = PadesCms.sha256("conteudo".toByteArray())
        val sig = pki.rawSign(hash)
        server.enqueue(MockResponse().setBody("""{"certificate_alias":"a","signatures":[{"id":"1","raw_signature":"${Base64.getEncoder().encodeToString(sig)}"}]}"""))
        val out = client().signHash(AccessToken("t", "Bearer", Long.MAX_VALUE, null), "a", hash, "planta.pdf")
        assertArrayEquals(sig, out)
        val req = server.takeRequest()
        assertEquals("/api/v0/oauth/signature", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("a", body["certificate_alias"]!!.jsonPrimitive.content)
        val h = body["hashes"]!!.jsonArray[0].jsonObject
        assertEquals(Base64.getEncoder().encodeToString(hash), h["hash"]!!.jsonPrimitive.content)
        assertEquals(PscApiClient.OID_SHA256, h["hash_algorithm"]!!.jsonPrimitive.content)
        assertEquals("RAW", h["signature_format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chave do app recusada na troca nao vira sessao expirada`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        val e = assertThrows<SigningException.NotConfigured> { client(TokenMode.BROKER).exchangeCode("cod", Pkce("ver", "x")) }
        assertTrue(e.message!!.contains("SAFEID_BROKERKEY"))
    }

    @Test
    fun `codigo recusado pelo PSC vira autorizacao vencida`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"upstream_401","detail":"invalid_grant"}"""))
        assertThrows<SigningException.CodeExpired> { client(TokenMode.BROKER).exchangeCode("cod", Pkce("ver", "x")) }
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"upstream_400","detail":"invalid_request"}"""))
        assertThrows<SigningException.Http> { client(TokenMode.BROKER).exchangeCode("cod", Pkce("ver", "x")) }
    }

    @Test
    fun `erros viram excecoes especificas`() = runTest {
        val token = AccessToken("t", "Bearer", Long.MAX_VALUE, null)
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows<SigningException.SessionExpired> { client().discoverCertificates(token) }
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"falha interna"}"""))
        val http = assertThrows<SigningException.Http> { client().discoverCertificates(token) }
        assertTrue(http.message!!.contains("falha interna"))
        server.enqueue(MockResponse().setBody("nao e json"))
        assertThrows<SigningException.InvalidResponse> { client().discoverCertificates(token) }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThrows<SigningException.Network> { client().discoverCertificates(token) }
    }

    @Test
    fun `assinante PSC recusa assinatura que nao confere`() = runTest {
        val other = TestPki()
        val hash = PadesCms.sha256("x".toByteArray())
        val wrong = other.rawSign(hash)
        server.enqueue(MockResponse().setBody("""{"signatures":[{"id":"1","raw_signature":"${Base64.getEncoder().encodeToString(wrong)}"}]}"""))
        val signer = PscRemoteSigner(client(), AccessToken("t", "Bearer", Long.MAX_VALUE, null), CloudCertificate("a", pki.holderCert, listOf(pki.holderCert)), "doc")
        assertThrows<SigningException.InvalidResponse> { signer.signDigest(hash) }
    }

    @Test
    fun `configuracao exige https e client_id`() {
        assertThrows<IllegalArgumentException> { PscConfig(baseUrl = "http://inseguro", clientId = "c", tokenMode = TokenMode.DIRECT_PKCE) }
        assertThrows<IllegalArgumentException> { PscConfig(baseUrl = "https://ok", clientId = "", tokenMode = TokenMode.DIRECT_PKCE) }
        assertThrows<IllegalArgumentException> { PscConfig(baseUrl = "https://ok", clientId = "c", tokenMode = TokenMode.BROKER, brokerUrl = null) }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("esperado ${T::class.simpleName}, veio ${e::class.simpleName}: ${e.message}", e)
        }
        throw AssertionError("esperado ${T::class.simpleName}, nada lançado")
    }
}
