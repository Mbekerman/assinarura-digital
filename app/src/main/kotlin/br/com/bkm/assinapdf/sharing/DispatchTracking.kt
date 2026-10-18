package br.com.bkm.assinapdf.sharing

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import br.com.bkm.assinapdf.app.AssinaPdfApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Marca documentos como despachados quando o usuário escolhe um app no menu de compartilhar.
 * O Android avisa só a escolha do app (EXTRA_CHOSEN_COMPONENT), não se a mensagem foi enviada;
 * abrir o menu e cancelar não marca nada.
 */
object DispatchTracking {
    const val EXTRA_IDS = "br.com.bkm.assinapdf.extra.DOCUMENTOS"

    /** IntentSender para Intent.createChooser: dispara [ShareChosenReceiver] com os [ids]. */
    fun sender(context: Context, ids: List<String>): IntentSender {
        val intent = Intent(context, ShareChosenReceiver::class.java).putExtra(EXTRA_IDS, ids.toTypedArray())
        // mutável: o sistema acrescenta o componente escolhido; o destino é explícito (receptor interno)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, ids.joinToString(",").hashCode(), intent, flags).intentSender
    }
}

/** Recebe a escolha do app no menu de compartilhar e marca os documentos como despachados. */
class ShareChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getStringArrayExtra(DispatchTracking.EXTRA_IDS)?.toList().orEmpty()
        if (ids.isEmpty()) return
        val pending = goAsync()
        val settings = (context.applicationContext as AssinaPdfApp).container.settings
        CoroutineScope(Dispatchers.IO).launch {
            try {
                settings.markDispatched(ids)
            } finally {
                pending.finish()
            }
        }
    }
}
