package br.com.bkm.assinapdf

import android.content.Intent
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.bkm.assinapdf.app.AssinaPdfApp
import br.com.bkm.assinapdf.app.MainActivity
import br.com.bkm.assinapdf.data.RecentDocument
import br.com.bkm.assinapdf.stamps.EditingSession
import br.com.bkm.assinapdf.ui.AppUiState
import br.com.bkm.assinapdf.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Aba "Rascunhos" na tela inicial e setas laterais do editor para o rascunho anterior/próximo. */
@RunWith(AndroidJUnit4::class)
class DraftNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AssinaPdfApp).container

    private fun draft(id: String, name: String) = runBlocking {
        val original = container.storage.originalFile(id)
        TestDocuments.create(context, "$id.pdf").copyTo(original, overwrite = true)
        container.sessions.save(EditingSession(documentId = id, originalName = name, localPath = original.absolutePath, pages = emptyList()))
        container.settings.upsertRecent(RecentDocument(id, name, System.currentTimeMillis()))
    }

    @Test
    fun setasLateraisAbremORascunhoVizinho() {
        draft("draft-a", "rascunho_a.pdf")
        draft("draft-b", "rascunho_b.pdf") // mais recente: primeiro da aba
        val main = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        ActivityScenario.launch<MainActivity>(main).use { scenario ->
            fun state(): AppUiState {
                var s: AppUiState? = null
                scenario.onActivity { s = it.appViewModel.state.value }
                return s!!
            }
            compose.waitUntil(15_000) { state().draftIds.take(2) == listOf("draft-b", "draft-a") }

            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Rascunhos", substring = true))
            compose.onNodeWithText("Rascunhos", substring = true).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("rascunho_b.pdf"))
            // aparece também no cartão "Continuar edição"; os dois abrem o mesmo rascunho
            compose.onAllNodesWithText("rascunho_b.pdf").onFirst().performClick()
            compose.waitUntil(15_000) { state().screen == Screen.EDITOR && state().document?.session?.documentId == "draft-b" && state().loading == null }

            // primeiro da lista: só há seta para o próximo
            assertEquals(0, compose.onAllNodesWithContentDescription("Rascunho anterior", substring = true).fetchSemanticsNodes().size)
            compose.onNodeWithContentDescription("Próximo rascunho", substring = true).performClick()
            compose.waitUntil(15_000) { state().document?.session?.documentId == "draft-a" && state().loading == null }

            compose.onNodeWithContentDescription("Rascunho anterior", substring = true).performClick()
            compose.waitUntil(15_000) { state().document?.session?.documentId == "draft-b" && state().loading == null }
        }
    }
}
