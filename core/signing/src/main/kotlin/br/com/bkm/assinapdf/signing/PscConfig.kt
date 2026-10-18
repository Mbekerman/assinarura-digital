package br.com.bkm.assinapdf.signing

/**
 * Configuração da integração com o PSC (Prestador de Serviço de Confiança) do certificado em nuvem.
 *
 * Os caminhos padrão seguem a API padronizada para PSCs da ICP-Brasil (DOC-ICP-17.01), como é
 * descrita publicamente por integradores de BirdID, VIDaaS e outros PSCs. A documentação
 * específica da Safeweb (https://pscsafeweb.safewebpss.com.br/Docs/) não pôde ser consultada a
 * partir do ambiente de desenvolvimento, então base, caminhos, nomes de campos e o uso de PKCE
 * PRECISAM ser confirmados nela antes do uso real. Por isso todos são configuráveis e a URL base
 * não tem valor padrão.
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
    /** Validade pedida para o token, em segundos (o PSC pode limitar). */
    val lifetimeSeconds: Int = 900,
    /** Como trocar o código de autorização pelo token. */
    val tokenMode: TokenMode = TokenMode.BROKER,
    /** URL do serviço intermediário mínimo (módulo server/token-broker), quando [tokenMode] = BROKER. */
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

/** Caminhos relativos à URL base. Padrões: API PSC ICP-Brasil (a confirmar na documentação Safeweb). */
data class PscPaths(
    val authorize: String = "v0/oauth/authorize",
    val token: String = "v0/oauth/token",
    val certificateDiscovery: String = "v0/oauth/certificate-discovery",
    val signature: String = "v0/oauth/signature",
)

/** Nomes de campos que variam entre descrições da API; ajustáveis sem recompilar a lógica. */
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
)
