package br.com.bkm.assinapdf.stamps

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Situação da assinatura digital de um documento. */
@Serializable
enum class SignatureStatus { NOT_SIGNED, DRAFT_EXPORTED, SIGNING, SIGNED, FAILED }

/**
 * Estado persistível da edição de um documento, para recuperação caso o Android encerre o
 * aplicativo. Guarda apenas caminhos locais e geometria; nunca conteúdo do PDF.
 */
@Serializable
data class EditingSession(
    val documentId: String,
    val originalName: String,
    val sourceUri: String? = null,
    val localPath: String,
    val pages: List<PageGeometry>,
    val sheet: StampSheet = StampSheet(),
    val currentPage: Int = 0,
    val addMode: Boolean = false,
    val signatureStatus: SignatureStatus = SignatureStatus.NOT_SIGNED,
    val signedPath: String? = null,
    val hasExistingSignatures: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
) {
    val pageCount: Int get() = pages.size

    fun toJson(): String = json.encodeToString(this)

    companion object {
        val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): EditingSession = json.decodeFromString(text)
    }
}
