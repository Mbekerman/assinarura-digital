package br.com.bkm.assinapdf.signing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) com método S256. */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        private val random = SecureRandom()

        fun generate(): Pkce {
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            val verifier = base64Url(bytes)
            return Pkce(verifier, challengeFor(verifier))
        }

        fun challengeFor(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun randomState(): String = base64Url(ByteArray(24).also { random.nextBytes(it) })

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
