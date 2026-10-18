package br.com.bkm.assinapdf.app

import android.content.Context
import br.com.bkm.assinapdf.data.AppStorage
import br.com.bkm.assinapdf.data.SecureStore
import br.com.bkm.assinapdf.data.SessionStore
import br.com.bkm.assinapdf.data.SettingsRepository
import br.com.bkm.assinapdf.sharing.DocumentImporter
import br.com.bkm.assinapdf.signing.SigningService

/** Dependências do app, criadas uma vez (injeção manual, sem framework). */
class AppContainer(context: Context) {
    val storage = AppStorage(context)
    val settings = SettingsRepository(context)
    val sessions = SessionStore(storage)
    val secure = SecureStore(storage.secureDir)
    val importer = DocumentImporter(context, storage)
    val signing = SigningService(storage, secure)
}
