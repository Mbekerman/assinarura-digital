package br.com.bkm.assinapdf.signing

/** Falhas do fluxo de assinatura, com mensagens em português para a interface. */
sealed class SigningException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured(detail: String) : SigningException("Integração SafeID não configurada: $detail")
    class Canceled : SigningException("Autorização cancelada. O documento e os carimbos foram preservados.")
    class Network(cause: Throwable) : SigningException("Sem conexão com o serviço de assinatura. Verifique a internet e tente de novo.", cause)
    class SessionExpired : SigningException("A autorização expirou. Autorize novamente no SafeID.")
    class CodeExpired : SigningException(
        "A autorização venceu antes de chegar ao app: o código do SafeID vale poucos minutos. Autorize de novo e, " +
            "assim que aparecer \"Autorização recebida\", toque em Continuar.",
    )
    class CertificateUnavailable(detail: String) : SigningException(detail)
    class InvalidResponse(detail: String) : SigningException("Resposta inválida do serviço de assinatura: $detail")
    class Http(val code: Int, detail: String) : SigningException("O serviço de assinatura recusou a operação (HTTP $code). $detail")
    class Crypto(detail: String, cause: Throwable? = null) : SigningException("Erro no processamento criptográfico: $detail", cause)
    class NotValidated(detail: String) : SigningException("A assinatura não foi validada: $detail. O arquivo não foi marcado como assinado.")
    class UnsupportedDocument(detail: String) : SigningException(detail)
}
