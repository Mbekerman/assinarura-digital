package br.com.bkm.assinapdf.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.com.bkm.assinapdf.stamps.StampDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "configuracoes")

/** Preferências do usuário (sem dados sensíveis). */
data class AppSettings(
    val addMode: Boolean = false,
    /** Percentual padrão (fração) da largura da página para o carimbo, ajustável nas configurações. */
    val defaultWidthFraction: Double = StampDefaults.DEFAULT_WIDTH_FRACTION,
    /** Últimos tamanho e rotação usados, herdados pelos próximos carimbos. */
    val lastWidthFraction: Double = StampDefaults.DEFAULT_WIDTH_FRACTION,
    val lastRotationDeg: Double = 0.0,
    val whiteBackground: Boolean = false,
    val showMeasurements: Boolean = true,
    /** Nome do titular do último certificado consultado no SafeID, para a prévia do carimbo. */
    val lastHolderName: String? = null,
)

@Serializable
data class RecentDocument(
    val id: String,
    val name: String,
    val openedAtMs: Long,
    val signed: Boolean = false,
    val signedFileName: String? = null,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val addMode = booleanPreferencesKey("modo_adicionar")
        val defaultWidth = doublePreferencesKey("largura_padrao")
        val lastWidth = doublePreferencesKey("ultima_largura")
        val lastRotation = doublePreferencesKey("ultima_rotacao")
        val whiteBackground = booleanPreferencesKey("fundo_branco")
        val showMeasurements = booleanPreferencesKey("mostrar_medidas")
        val lastHolder = stringPreferencesKey("ultimo_titular")
        val recents = stringPreferencesKey("recentes")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val recentsSerializer = ListSerializer(RecentDocument.serializer())

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            addMode = p[Keys.addMode] ?: false,
            defaultWidthFraction = p[Keys.defaultWidth] ?: StampDefaults.DEFAULT_WIDTH_FRACTION,
            lastWidthFraction = p[Keys.lastWidth] ?: p[Keys.defaultWidth] ?: StampDefaults.DEFAULT_WIDTH_FRACTION,
            lastRotationDeg = p[Keys.lastRotation] ?: 0.0,
            whiteBackground = p[Keys.whiteBackground] ?: false,
            showMeasurements = p[Keys.showMeasurements] ?: true,
            lastHolderName = p[Keys.lastHolder],
        )
    }

    val recents: Flow<List<RecentDocument>> = context.settingsStore.data.map { p ->
        p[Keys.recents]?.let { runCatching { json.decodeFromString(recentsSerializer, it) }.getOrNull() } ?: emptyList()
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setAddMode(value: Boolean) = context.settingsStore.edit { it[Keys.addMode] = value }
    suspend fun setDefaultWidth(fraction: Double) = context.settingsStore.edit {
        it[Keys.defaultWidth] = fraction
        it[Keys.lastWidth] = fraction
    }
    suspend fun setLastUsed(defaults: StampDefaults) = context.settingsStore.edit {
        it[Keys.lastWidth] = defaults.widthFraction
        it[Keys.lastRotation] = defaults.rotationDeg
    }
    suspend fun setWhiteBackground(value: Boolean) = context.settingsStore.edit { it[Keys.whiteBackground] = value }
    suspend fun setShowMeasurements(value: Boolean) = context.settingsStore.edit { it[Keys.showMeasurements] = value }
    suspend fun setLastHolder(name: String?) = context.settingsStore.edit {
        if (name == null) it.remove(Keys.lastHolder) else it[Keys.lastHolder] = name
    }

    suspend fun upsertRecent(doc: RecentDocument) = context.settingsStore.edit { p ->
        val list = p[Keys.recents]?.let { runCatching { json.decodeFromString(recentsSerializer, it) }.getOrNull() } ?: emptyList()
        val updated = (listOf(doc) + list.filterNot { it.id == doc.id }).take(MAX_RECENTS)
        p[Keys.recents] = json.encodeToString(recentsSerializer, updated)
    }

    suspend fun removeRecent(id: String) = context.settingsStore.edit { p ->
        val list = p[Keys.recents]?.let { runCatching { json.decodeFromString(recentsSerializer, it) }.getOrNull() } ?: emptyList()
        p[Keys.recents] = json.encodeToString(recentsSerializer, list.filterNot { it.id == id })
    }

    companion object {
        const val MAX_RECENTS = 12
    }
}
