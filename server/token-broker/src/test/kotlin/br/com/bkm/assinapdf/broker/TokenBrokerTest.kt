package br.com.bkm.assinapdf.broker

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class TokenBrokerTest {
    private lateinit var fakePsc: HttpServer
    private lateinit var broker: TokenBroker
    @Volatile private var lastForm: String = ""
    private val http = HttpClient.newHttpClient()

    @Before
    fun setUp() {
        fakePsc = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fakePsc.createContext("/v0/oauth/token") { ex ->
            lastForm = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val body = if (lastForm.contains("code=bom")) """{"access_token":"tok","token_type":"Bearer","expires_in":600,"scope":"signature_session","refresh_token":"nao-repassar"}""" else """{"error":"invalid_grant"}"""
            val status = if (lastForm.contains("code=bom")) 200 else 400
            ex.sendResponseHeaders(status, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        fakePsc.start()
        broker = TokenBroker(
            BrokerConfig(
                tokenUrl = "http://127.0.0.1:${fakePsc.address.port}/v0/oauth/token",
                clientId = "cid",
                clientSecret = "segredo-do-integrador",
                appKey = "chave-do-app-1234567",
                allowedRedirects = setOf("assinapdf://oauth/callback"),
                port = 0,
            ),
        ).start()
    }

    @After
    fun tearDown() {
        broker.stop()
        fakePsc.stop(0)
    }

    private fun post(body: String, key: String = "chave-do-app-1234567"): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:${broker.port}/v1/token"))
            .header("X-AssinaPDF-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `troca o codigo adicionando o segredo e filtra a resposta`() {
        val r = post("""{"code":"bom","code_verifier":"ver","redirect_uri":"assinapdf://oauth/callback"}""")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"access_token\":\"tok\""))
        assertTrue(!r.body().contains("refresh_token"))
        assertTrue(lastForm.contains("client_secret=segredo-do-integrador"))
        assertTrue(lastForm.contains("code_verifier=ver"))
    }

    @Test
    fun `recusa chave errada, redirect fora da lista e erro do PSC`() {
        assertEquals(401, post("""{"code":"bom","code_verifier":"v","redirect_uri":"assinapdf://oauth/callback"}""", key = "errada").statusCode())
        assertEquals(400, post("""{"code":"bom","code_verifier":"v","redirect_uri":"https://evil/cb"}""").statusCode())
        assertEquals(400, post("""{"code":"ruim","code_verifier":"v","redirect_uri":"assinapdf://oauth/callback"}""").statusCode())
        assertEquals(400, post("nao json").statusCode())
    }
}
