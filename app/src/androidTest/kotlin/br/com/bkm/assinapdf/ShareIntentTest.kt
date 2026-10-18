package br.com.bkm.assinapdf

import android.content.ClipData
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.bkm.assinapdf.app.MainActivity
import br.com.bkm.assinapdf.stamps.PaperFormat
import br.com.bkm.assinapdf.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Teste A: um PDF compartilhado por ACTION_SEND com URI content:// (como o WhatsApp faz) abre
 * direto no editor, sem passar pela tela inicial.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sendIntent(name: String, widthPt: Int = 595, heightPt: Int = 842): Intent {
        val file = TestDocuments.create(context, name, widthPt, heightPt)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Test
    fun pdfCompartilhadoAbreDiretoNoEditor() {
        ActivityScenario.launch<MainActivity>(sendIntent("planta-whatsapp.pdf", 2384, 1684)).use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithText("planta-whatsapp.pdf").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { activity ->
                val state = activity.appViewModel.state.value
                assertEquals(Screen.EDITOR, state.screen)
                assertEquals(PaperFormat.A1, state.document!!.info.pages[0].paperFormat)
            }
        }
    }

    @Test
    fun novoCompartilhamentoComAppAbertoUsaOnNewIntent() {
        ActivityScenario.launch<MainActivity>(sendIntent("primeiro.pdf")).use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithText("primeiro.pdf").fetchSemanticsNodes().isNotEmpty() }
            // com launchMode singleTask, um novo Intent chega por onNewIntent
            context.startActivity(sendIntent("segundo.pdf"))
            compose.waitUntil(15_000) { compose.onAllNodesWithText("segundo.pdf").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { assertEquals("segundo.pdf", it.appViewModel.state.value.document!!.session.originalName) }
        }
    }
}
