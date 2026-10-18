package br.com.bkm.assinapdf.data

import br.com.bkm.assinapdf.stamps.EditingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persiste o estado da edição (geometria dos carimbos e caminhos locais) para recuperar o trabalho
 * se o Android encerrar o app. Escrita atômica: grava em arquivo temporário e renomeia.
 */
class SessionStore(private val storage: AppStorage) {

    suspend fun save(session: EditingSession) = withContext(Dispatchers.IO) {
        storage.sessionsDir.mkdirs()
        val target = File(storage.sessionsDir, "${session.documentId}.json")
        val tmp = File(storage.sessionsDir, "${session.documentId}.json.tmp")
        tmp.writeText(session.copy(updatedAtEpochMs = System.currentTimeMillis()).toJson())
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    suspend fun load(id: String): EditingSession? = withContext(Dispatchers.IO) {
        val f = File(storage.sessionsDir, "$id.json")
        if (!f.exists()) return@withContext null
        runCatching { EditingSession.fromJson(f.readText()) }.getOrNull()?.takeIf { File(it.localPath).exists() }
    }

    /** Sessão mais recente ainda não assinada, para oferecer "Continuar edição". */
    suspend fun latestUnfinished(): EditingSession? = withContext(Dispatchers.IO) {
        storage.sessionsDir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.asSequence()
            ?.mapNotNull { runCatching { EditingSession.fromJson(it.readText()) }.getOrNull() }
            ?.firstOrNull { File(it.localPath).exists() && it.signatureStatus != br.com.bkm.assinapdf.stamps.SignatureStatus.SIGNED }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(storage.sessionsDir, "$id.json").delete()
    }
}
