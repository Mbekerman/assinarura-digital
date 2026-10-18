package br.com.bkm.assinapdf.sharing

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** O que chegou para o app por Intent. */
sealed interface Incoming {
    data class Pdf(val uri: Uri) : Incoming
    data class AuthRedirect(val uri: Uri) : Incoming
    data object None : Incoming
}

object IncomingIntents {
    /**
     * Interpreta ACTION_SEND (EXTRA_STREAM ou ClipData, como o WhatsApp envia), ACTION_VIEW de PDF
     * (content://) e o retorno da autorização OAuth.
     */
    fun parse(intent: Intent?, redirectUri: String): Incoming {
        if (intent == null) return Incoming.None
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                stream?.let { Incoming.Pdf(it) } ?: Incoming.None
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return Incoming.None
                if (isRedirect(data, redirectUri)) {
                    Incoming.AuthRedirect(data)
                } else if (data.scheme == "content" || data.scheme == "file") {
                    Incoming.Pdf(data)
                } else {
                    Incoming.None
                }
            }
            else -> Incoming.None
        }
    }

    private fun isRedirect(data: Uri, redirectUri: String): Boolean {
        val expected = Uri.parse(redirectUri)
        return data.scheme.equals(expected.scheme, ignoreCase = true) &&
            data.host.equals(expected.host, ignoreCase = true) &&
            (expected.path.isNullOrEmpty() || data.path == expected.path)
    }
}
