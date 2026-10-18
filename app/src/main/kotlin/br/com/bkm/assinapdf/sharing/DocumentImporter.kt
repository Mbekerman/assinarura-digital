package br.com.bkm.assinapdf.sharing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import br.com.bkm.assinapdf.data.AppStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

data class ImportedDocument(
    val id: String,
    val displayName: String,
    val file: File,
    val sourceUri: String,
)

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Copia o PDF recebido para a pasta privada do app usando ContentResolver, enquanto a permissão
 * temporária concedida pelo app remetente (ex.: WhatsApp) ainda vale. Não depende de caminho físico.
 */
class DocumentImporter(private val context: Context, private val storage: AppStorage) {

    suspend fun import(uri: Uri): ImportedDocument = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "documento.pdf"
        val id = UUID.randomUUID().toString()
        val dest = storage.originalFile(id)
        try {
            val input = resolver.openInputStream(uri) ?: throw ImportException("Não foi possível ler o arquivo recebido.")
            input.use { src -> dest.outputStream().use { out -> src.copyTo(out, 256 * 1024) } }
        } catch (e: SecurityException) {
            storage.deleteDocument(id)
            throw ImportException("O app de origem não permitiu a leitura do arquivo. Compartilhe novamente.", e)
        } catch (e: FileNotFoundException) {
            storage.deleteDocument(id)
            throw ImportException("Arquivo não encontrado. Ele pode ter sido apagado no app de origem.", e)
        } catch (e: ImportException) {
            storage.deleteDocument(id)
            throw e
        } catch (e: Exception) {
            storage.deleteDocument(id)
            throw ImportException("Falha ao copiar o arquivo recebido.", e)
        }
        if (!looksLikePdf(dest)) {
            storage.deleteDocument(id)
            throw ImportException("O arquivo recebido não é um PDF.")
        }
        ImportedDocument(id, FileNames.ensurePdf(name), dest, uri.toString())
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** O cabeçalho %PDF deve estar no primeiro kilobyte (a especificação tolera lixo antes dele). */
    private fun looksLikePdf(file: File): Boolean {
        if (file.length() < 8) return false
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }
        return String(head, 0, maxOf(n, 0), Charsets.ISO_8859_1).contains("%PDF-")
    }
}
