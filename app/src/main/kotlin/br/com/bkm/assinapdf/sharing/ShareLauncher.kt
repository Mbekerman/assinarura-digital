package br.com.bkm.assinapdf.sharing

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentSender
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

    /** [onChosen] recebe a escolha do app no menu (veja [DispatchTracking]). */
    fun share(context: Context, file: File, chooserTitle: String = "Compartilhar PDF", onChosen: IntentSender? = null) {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle, onChosen).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }

    /** Vários PDFs de uma vez (ACTION_SEND_MULTIPLE); com um só, igual a [share]. */
    fun shareAll(context: Context, files: List<File>, chooserTitle: String = "Compartilhar PDFs", onChosen: IntentSender? = null) {
        require(files.isNotEmpty()) { "nenhum arquivo" }
        if (files.size == 1) return share(context, files[0], chooserTitle, onChosen)
        val uris = ArrayList(files.map { uriFor(context, it) })
        val clip = ClipData.newRawUri(files[0].name, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = MIME
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle, onChosen).apply {
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
