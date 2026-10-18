package br.com.bkm.assinapdf.signing

import br.com.bkm.assinapdf.BuildConfig

/** Lê a configuração do SafeID gerada a partir de signing-config.properties no build. */
object SafeIdSettings {
    data class Loaded(val config: PscConfig?, val problem: String?)

    fun load(): Loaded {
        val missing = buildList {
            if (BuildConfig.SAFEID_BASE_URL.isBlank()) add("safeid.baseUrl")
            if (BuildConfig.SAFEID_CLIENT_ID.isBlank()) add("safeid.clientId")
            if (BuildConfig.SAFEID_TOKEN_MODE == "BROKER" && BuildConfig.SAFEID_BROKER_URL.isBlank()) add("safeid.brokerUrl")
            if (BuildConfig.SAFEID_TOKEN_MODE == "BROKER" && BuildConfig.SAFEID_BROKER_KEY.isBlank()) add("safeid.brokerKey (segredo SAFEID_BROKERKEY)")
        }
        if (missing.isNotEmpty()) {
            return Loaded(
                null,
                "Faltam ${missing.joinToString()} na configuração do build. " +
                    "O editor funciona normalmente; a assinatura digital fica indisponível até o APK ser gerado com as credenciais da Safeweb.",
            )
        }
        val policy = if (BuildConfig.SAFEID_POLICY_OID.isNotBlank() && BuildConfig.SAFEID_POLICY_HASH.isNotBlank()) {
            SignaturePolicy(
                oid = BuildConfig.SAFEID_POLICY_OID,
                hashAlgorithmOid = BuildConfig.SAFEID_POLICY_HASH_ALG.ifBlank { PscApiClient.OID_SHA256 },
                hashBase64 = BuildConfig.SAFEID_POLICY_HASH,
                uri = BuildConfig.SAFEID_POLICY_URI.ifBlank { null },
            )
        } else {
            // padrão: PAdES AD-RB v1.3 oficial da ICP-Brasil
            SignaturePolicy.PADES_AD_RB_V1_3
        }
        return runCatching {
            PscConfig(
                baseUrl = BuildConfig.SAFEID_BASE_URL,
                clientId = BuildConfig.SAFEID_CLIENT_ID,
                redirectUri = BuildConfig.SAFEID_REDIRECT_URI,
                scope = BuildConfig.SAFEID_SCOPE,
                lifetimeSeconds = BuildConfig.SAFEID_LIFETIME,
                tokenMode = if (BuildConfig.SAFEID_TOKEN_MODE == "DIRECT_PKCE") TokenMode.DIRECT_PKCE else TokenMode.BROKER,
                brokerUrl = BuildConfig.SAFEID_BROKER_URL.ifBlank { null },
                brokerKey = BuildConfig.SAFEID_BROKER_KEY.ifBlank { null },
                paths = PscPaths(
                    authorize = BuildConfig.SAFEID_PATH_AUTHORIZE,
                    token = BuildConfig.SAFEID_PATH_TOKEN,
                    certificateDiscovery = BuildConfig.SAFEID_PATH_DISCOVERY,
                    signature = BuildConfig.SAFEID_PATH_SIGNATURE,
                ),
                dialect = PscDialect(hashAlgorithmField = BuildConfig.SAFEID_HASH_ALG_FIELD),
                signaturePolicy = policy,
            )
        }.fold(
            onSuccess = { Loaded(it, null) },
            onFailure = { Loaded(null, "Configuração do SafeID inválida: ${it.message}") },
        )
    }
}
