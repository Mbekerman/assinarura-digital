package br.com.bkm.assinapdf

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.bkm.assinapdf.app.MainActivity
import br.com.bkm.assinapdf.stamps.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Teste E e G: com "Adicionar assinaturas" ativo, dez duplos toques criam dez carimbos
 * independentes, com o mesmo percentual da largura; o zoom não altera o tamanho real.
 */
@RunWith(AndroidJUnit4::class)
class AddStampsGestureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dezDuplosToquesCriamDezCarimbos() {
        val file = TestDocuments.create(context, "multiplos.pdf")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(context, MainActivity::class.java)
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithText("multiplos.pdf").fetchSemanticsNodes().isNotEmpty() }
            var addMode = false
            scenario.onActivity { addMode = it.appViewModel.state.value.settings.addMode }
            if (!addMode) compose.onNodeWithText("Adicionar assinaturas").performClick()
            compose.waitUntil(5_000) {
                var on = false
                scenario.onActivity { on = it.appViewModel.state.value.settings.addMode }
                on
            }
            compose.waitForIdle()
            val canvas = compose.onNodeWithContentDescription("Documento PDF")
            for (i in 0 until 10) {
                val col = i % 2
                val row = i / 2
                canvas.performTouchInput {
                    doubleClick(Offset(width * (0.28f + 0.44f * col), height * (0.22f + 0.08f * row)))
                }
                compose.waitForIdle()
            }
            scenario.onActivity { a ->
                val stamps = a.appViewModel.state.value.sheet.stamps
                assertEquals(10, stamps.size)
                assertEquals(10, stamps.map { it.id }.toSet().size)
                stamps.forEach { assertEquals(stamps.first().widthFraction, it.widthFraction, 1e-9) }
            }
            // zoom por pinça não altera os carimbos
            var before: List<Stamp> = emptyList()
            scenario.onActivity { before = it.appViewModel.state.value.sheet.stamps }
            canvas.performTouchInput {
                pinch(center + Offset(-50f, 0f), center + Offset(-200f, 0f), center + Offset(50f, 0f), center + Offset(200f, 0f))
            }
            compose.waitForIdle()
            scenario.onActivity { a ->
                assertEquals(before, a.appViewModel.state.value.sheet.stamps)
                assertTrue(a.appViewModel.viewport != null)
            }
        }
    }
}
