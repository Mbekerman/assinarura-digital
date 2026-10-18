package br.com.bkm.assinapdf.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class AssinaPdfApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Métricas das fontes padrão do PDF (Helvetica) usadas no carimbo
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
        container.storage.cleanupTemps()
    }
}
