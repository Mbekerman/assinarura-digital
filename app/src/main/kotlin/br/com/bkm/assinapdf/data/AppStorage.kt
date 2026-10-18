package br.com.bkm.assinapdf.data

import android.content.Context
import java.io.File

/**
 * Pastas privadas do app. Nada é gravado em armazenamento compartilhado: para salvar fora do app
 * o usuário escolhe o destino pelo seletor do sistema (Storage Access Framework).
 *
 * docs/<id>/original.pdf  cópia privada do PDF recebido
 * docs/<id>/(nome).tmp.pdf  arquivos intermediários (apagados ao terminar)
 * docs/<id>/out/          PDFs assinados e rascunhos (expostos só via FileProvider)
 * sessions/<id>.json      estado da edição, para recuperação
 */
class AppStorage(context: Context) {
    private val filesDir: File = context.filesDir
    val docsDir: File = File(filesDir, "docs")
    val sessionsDir: File = File(filesDir, "sessions")
    val secureDir: File = File(filesDir, "secure")

    fun docDir(id: String): File = File(docsDir, safeId(id)).also { it.mkdirs() }
    fun originalFile(id: String): File = File(docDir(id), "original.pdf")
    fun outDir(id: String): File = File(docDir(id), "out").also { it.mkdirs() }
    fun tempFile(id: String, name: String): File = File(docDir(id), "$name.tmp.pdf")

    /** Arquivo de saída com o nome desejado, sem sobrescrever um existente. */
    fun uniqueOutput(id: String, fileName: String): File {
        val dir = outDir(id)
        var candidate = File(dir, fileName)
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, fileName.removeSuffix(".pdf") + " ($n).pdf")
            n++
        }
        return candidate
    }

    /** Apaga arquivos intermediários de todos os documentos. */
    fun cleanupTemps() {
        docsDir.listFiles()?.forEach { dir ->
            dir.listFiles { f -> f.name.endsWith(".tmp.pdf") || f.name.endsWith(".partial") }?.forEach { it.delete() }
        }
    }

    fun deleteDocument(id: String) {
        File(docsDir, safeId(id)).deleteRecursively()
        File(sessionsDir, "${safeId(id)}.json").delete()
    }

    /** Remove documentos antigos que não estão na lista [keepIds]. */
    fun deleteOlderThan(maxAgeMs: Long, keepIds: Set<String>) {
        val limit = System.currentTimeMillis() - maxAgeMs
        docsDir.listFiles()?.forEach { dir ->
            if (dir.name !in keepIds && dir.lastModified() < limit) deleteDocument(dir.name)
        }
    }

    private fun safeId(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "doc" }
}
