package br.com.bkm.assinapdf

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.bkm.assinapdf.app.AssinaPdfApp
import br.com.bkm.assinapdf.app.MainActivity
import br.com.bkm.assinapdf.data.RecentDocument
import br.com.bkm.assinapdf.stamps.EditingSession
import br.com.bkm.assinapdf.stamps.SignatureStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Na tela inicial, toque longo num PDF assinado inicia a seleção; toques marcam outros; o botão
 * envia todos de uma vez por ACTION_SEND_MULTIPLE.
 */
@RunWith(AndroidJUnit4::class)
class ShareSelectedTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AssinaPdfApp).container

    /** Documento já assinado, como o app deixa depois de uma assinatura. */
    private fun signedDocument(id: String, name: String) = runBlocking {
        val original = container.storage.originalFile(id)
        TestDocuments.create(context, "$id.pdf").copyTo(original, overwrite = true)
        val out = container.storage.uniqueOutput(id, name)
        original.copyTo(out)
        container.sessions.save(
            EditingSession(
                documentId = id,
                originalName = name,
                localPath = original.absolutePath,
                pages = emptyList(),
                signatureStatus = SignatureStatus.SIGNED,
                signedPath = out.absolutePath,
            ),
        )
        container.settings.upsertRecent(RecentDocument(id, name, System.currentTimeMillis(), signed = true, signedFileName = name))
    }

    @Test
    fun toqueLongoSelecionaVariosECompartilhaDeUmaVez() {
        signedDocument("sel-a", "contrato_assinado.pdf")
        signedDocument("sel-b", "planta_assinado.pdf")
        Intents.init()
        try {
            // não abre o seletor do sistema; só registra o Intent
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            val main = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            ActivityScenario.launch<MainActivity>(main).use { scenario ->
                compose.waitUntil(15_000) {
                    var ids = emptyList<String>()
                    scenario.onActivity { a -> ids = a.appViewModel.state.value.recents.map { it.id } }
                    ids.containsAll(listOf("sel-a", "sel-b"))
                }

                // a lista é preguiçosa: em tela pequena os recentes podem estar abaixo da dobra
                fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

                scrollTo("contrato_assinado.pdf")
                compose.onNodeWithText("contrato_assinado.pdf").performTouchInput { longClick() }
                // barra de seleção fixa no topo, visível mesmo com a lista rolada
                compose.onNodeWithText("1 selecionado").assertExists()
                compose.onNodeWithText("Compartilhar 1 PDF").assertExists()
                scrollTo("planta_assinado.pdf")
                compose.onNodeWithText("planta_assinado.pdf").performClick()
                compose.onNodeWithText("2 selecionados").assertExists()
                compose.onNodeWithText("Compartilhar 2 PDFs").performClick()

                compose.waitUntil(10_000) { Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER } }
                val chooser = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
                val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
                assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
                assertEquals("application/pdf", send.type)
                val uris = IntentCompat.getParcelableArrayListExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
                assertEquals(2, uris.size)
                assertEquals(2, send.clipData!!.itemCount)
                assertEquals(
                    listOf("contrato_assinado.pdf", "planta_assinado.pdf"),
                    uris.map { it.lastPathSegment },
                )

                // depois de compartilhar, a seleção termina
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Compartilhar 2 PDFs").fetchSemanticsNodes().isEmpty() }
            }
        } finally {
            Intents.release()
        }
    }
}
