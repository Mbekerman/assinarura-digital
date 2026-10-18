package br.com.bkm.assinapdf.sharing

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Compartilhamento e abertura do PDF final pelo Sharesheet do Android, com URI do FileProvider e
 * permissão temporária de leitura. O envio no WhatsApp é feito pelo próprio usuário.
 */
object ShareLauncher {
    private const val MIME = "application/pdf"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun share(context: Context, file: File, chooserTitle: String = "Compartilhar PDF") {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }

    /** Abre o PDF em outro leitor. Devolve false se não houver app para PDF. */
    fun open(context: Context, file: File): Boolean {
        val uri = uriFor(context, file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Copia o PDF para um destino escolhido no seletor do sistema (ACTION_CREATE_DOCUMENT). */
    fun copyTo(context: Context, file: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination, "w")?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("destino indisponível")
    }
}
