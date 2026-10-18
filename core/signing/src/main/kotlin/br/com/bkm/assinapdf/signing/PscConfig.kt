package br.com.bkm.assinapdf.signing

/**
 * Configuração da integração com o PSC (Prestador de Serviço de Confiança) do certificado em nuvem.
 *
 * Os caminhos padrão são os da documentação oficial do SafeID
 * (https://pscsafeweb.safewebpss.com.br/Docs/), relativos à URL base
 * `https://pscsafeweb.safewebpss.com.br/Service/Microservice/OAuth/api/v0/oauth`.
 */
data class PscConfig(
    /** URL base do PSC, por exemplo a informada no painel SafeID Integração. Obrigatória. */
    val baseUrl: String,
    /** client_id da aplicação cadastrada no painel de integração. Não é segredo. */
    val clientId: String,
    /** URI de retorno cadastrada no painel (HTTPS; o app a abre por App Link). */
    val redirectUri: String = DEFAULT_REDIRECT_URI,
    /** Escopo pedido na autorização. "signature_session" permite várias assinaturas durante [lifetimeSeconds]. */
    val scope: String = "signature_session",
    /** Validade pedida para o token, em segundos. O SafeID limita a 7 dias para pessoa física; sem o parâmetro, 5 minutos. */
    val lifetimeSeconds: Int = 604_800,
    /** Como trocar o código de autorização pelo token. */
    val tokenMode: TokenMode = TokenMode.BROKER,
    /** URL do serviço intermediário mínimo (server/retorno), quando [tokenMode] = BROKER. */
    val brokerUrl: String? = null,
    /** Chave de acesso ao serviço intermediário. Fica no APK; serve só para conter abuso, não é segredo forte. */
    val brokerKey: String? = null,
    val paths: PscPaths = PscPaths(),
    val dialect: PscDialect = PscDialect(),
    /** Política de assinatura ICP-Brasil (opcional); sem ela é gerado PAdES baseline sem identificador de política. */
    val signaturePolicy: SignaturePolicy? = null,
) {
    init {
        require(baseUrl.startsWith("https://")) { "A URL base do PSC deve usar HTTPS" }
        require(clientId.isNotBlank()) { "client_id não configurado" }
        if (tokenMode == TokenMode.BROKER) {
            require(!brokerUrl.isNullOrBlank() && brokerUrl.startsWith("https://")) {
                "Modo BROKER exige brokerUrl com HTTPS"
            }
        }
    }

    fun url(path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        const val DEFAULT_REDIRECT_URI = "assinapdf://oauth/callback"
    }
}

enum class TokenMode {
    /** O app troca o código direto no PSC usando apenas PKCE (só funciona se o PSC aceitar cliente público). */
    DIRECT_PKCE,

    /** O app envia código + verificador ao serviço intermediário, que adiciona o client_secret. */
    BROKER,
}

/** Caminhos relativos à URL base, conforme a documentação do SafeID. */
data class PscPaths(
    val authorize: String = "authorize",
    val token: String = "token",
    val certificateDiscovery: String = "certificate-discovery",
    val signature: String = "signature",
)

/** Nomes de campos e formato da assinatura; ajustáveis sem recompilar a lógica. */
data class PscDialect(
    val hashAlgorithmField: String = "hash_algorithm",
    val signatureFormat: SignatureFormat = SignatureFormat.RAW,
)

enum class SignatureFormat(val wire: String) {
    /** O PSC devolve a assinatura PKCS#1 v1.5 do hash; o app monta o CMS (recomendado para PAdES). */
    RAW("RAW"),

    /** O PSC devolve um CMS destacado sobre o hash enviado. */
    CMS("CMS"),
}

/** Identificador de política de assinatura (atributo id-aa-ets-sigPolicyId). Valores devem vir do ITI. */
data class SignaturePolicy(
    val oid: String,
    val hashAlgorithmOid: String,
    val hashBase64: String,
    val uri: String? = null,
) {
    companion object {
        /**
         * PAdES AD-RB v1.3 da ICP-Brasil, vigente desde 23/07/2025 (período de assinatura até
         * 22/10/2037 na LPA_PAdES). Tirada de http://politicas.icpbrasil.gov.br/PA_PAdES_AD_RB_v1_3.der:
         * o SHA-256 do arquivo confere com o da LPA (23DA544A…) e o hash abaixo é o campo
         * signPolicyHash do próprio arquivo, mesmo critério das assinaturas que o validador do ITI
         * aprova (conferido com uma AD-RT v1.3 real). A política exige content-type, message-digest,
         * signing-certificate-v2 e o próprio sigPolicyId, e aceita sha256WithRSAEncryption.
         */
        val PADES_AD_RB_V1_3 = SignaturePolicy(
            oid = "2.16.76.1.7.1.11.1.3",
            hashAlgorithmOid = "2.16.840.1.101.3.4.2.1",
            hashBase64 = "I+S+S5s2IXLk67DnK4ahM+zlqthD2GUcbjiguj8I/GA=",
            uri = "http://politicas.icpbrasil.gov.br/PA_PAdES_AD_RB_v1_3.der",
        )
    }
}
