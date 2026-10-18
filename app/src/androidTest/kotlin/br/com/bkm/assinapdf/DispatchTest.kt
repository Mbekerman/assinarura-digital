package br.com.bkm.assinapdf

import android.content.Intent
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.bkm.assinapdf.app.AssinaPdfApp
import br.com.bkm.assinapdf.app.MainActivity
import br.com.bkm.assinapdf.data.RecentDocument
import br.com.bkm.assinapdf.sharing.DispatchTracking
import br.com.bkm.assinapdf.stamps.EditingSession
import br.com.bkm.assinapdf.stamps.SignatureStatus
import br.com.bkm.assinapdf.ui.AppUiState
import br.com.bkm.assinapdf.ui.HomeTab
import br.com.bkm.assinapdf.ui.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Aba "Despachados" (escolha de app no compartilhamento) e botão "Próximo" na tela de conclusão. */
@RunWith(AndroidJUnit4::class)
class DispatchTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AssinaPdfApp).container

    private fun document(id: String, name: String, signed: Boolean) = runBlocking {
        val original = container.storage.originalFile(id)
        TestDocuments.create(context, "$id.pdf").copyTo(original, overwrite = true)
        val out = if (signed) container.storage.uniqueOutput(id, name).also { original.copyTo(it, overwrite = true) } else null
        container.sessions.save(
            EditingSession(
                documentId = id,
                originalName = name,
                localPath = original.absolutePath,
                pages = emptyList(),
                signatureStatus = if (signed) SignatureStatus.SIGNED else SignatureStatus.NOT_SIGNED,
                signedPath = out?.absolutePath,
            ),
        )
        container.settings.upsertRecent(RecentDocument(id, name, System.currentTimeMillis(), signed = signed, signedFileName = if (signed) name else null))
    }

    @Test
    fun escolherAppNoCompartilhamentoMoveParaDespachados() {
        document("desp-a", "despachar_assinado.pdf", signed = true)
        // o que o Android faz quando o usuário escolhe um app no menu de compartilhar
        DispatchTracking.sender(context, listOf("desp-a")).sendIntent(context, 0, null, null, null)
        runBlocking {
            val deadline = System.currentTimeMillis() + 10_000
            while (container.settings.recents.first().first { it.id == "desp-a" }.dispatchedAtMs == null) {
                check(System.currentTimeMillis() < deadline) { "não foi marcado como despachado" }
                Thread.sleep(100)
            }
        }
        val main = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        ActivityScenario.launch<MainActivity>(main).use { scenario ->
            fun state(): AppUiState {
                var s: AppUiState? = null
                scenario.onActivity { s = it.appViewModel.state.value }
                return s!!
            }
            compose.waitUntil(15_000) { state().recents.any { it.id == "desp-a" } }
            assertEquals(listOf("desp-a"), state().docsFor(HomeTab.DESPACHADOS).map { it.id }.filter { it == "desp-a" })
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Despachados"))
            compose.onNodeWithText("Despachados").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("despachar_assinado.pdf"))
            compose.onNodeWithText("despachar_assinado.pdf").assertExists()
        }
    }

    @Test
    fun proximoAbreORascunhoSeguinte() {
        document("prox-r", "proximo_rascunho.pdf", signed = false)
        document("prox-s", "proximo_assinado.pdf", signed = true) // mais recente: vem antes na lista
        val main = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        ActivityScenario.launch<MainActivity>(main).use { scenario ->
            fun state(): AppUiState {
                var s: AppUiState? = null
                scenario.onActivity { s = it.appViewModel.state.value }
                return s!!
            }
            compose.waitUntil(15_000) { state().recents.map { it.id }.containsAll(listOf("prox-r", "prox-s")) }
            scenario.onActivity { it.appViewModel.resume("prox-s") }
            compose.waitUntil(15_000) { state().screen == Screen.OUTPUT }
            assertEquals("prox-r", state().nextDraftAfter("prox-s"))
            compose.onNodeWithText("Próximo").performClick()
            compose.waitUntil(15_000) { state().screen == Screen.EDITOR && state().document?.session?.documentId == "prox-r" && state().loading == null }
        }
    }
}
