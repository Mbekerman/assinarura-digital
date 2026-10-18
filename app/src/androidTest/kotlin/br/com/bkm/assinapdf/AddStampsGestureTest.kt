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
 * Testes E e G: com "Adicionar assinaturas" ativo, dez duplos toques criam dez carimbos
 * independentes, cada um centrado no ponto tocado e com o mesmo percentual da largura da página;
 * o zoom por pinça não altera os carimbos.
 *
 * Os toques são definidos em coordenadas da página e convertidos para a tela pela transformação
 * atual do visualizador, para não depender do tamanho da tela do emulador.
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
            compose.waitUntil(5_000) {
                var ready = false
                scenario.onActivity { ready = it.appViewModel.viewport != null }
                ready
            }
            compose.waitForIdle()

            // Grade 2 × 5 na metade de cima da folha A4 (sempre visível com ajuste à largura).
            val targets = (0 until 10).map { i -> Pair(if (i % 2 == 0) 0.25 else 0.75, 0.06 + 0.10 * (i / 2)) }
            val canvas = compose.onNodeWithContentDescription("Documento PDF")
            val log = StringBuilder()
            for ((i, t) in targets.withIndex()) {
                var screen = Offset.Zero
                var before = 0
                scenario.onActivity { a ->
                    val vm = a.appViewModel
                    val v = vm.viewport!!
                    val slot = vm.layout!!.slots[0]
                    screen = Offset(
                        v.toScreenX(slot.left + t.first * slot.width).toFloat(),
                        v.toScreenY(slot.top + t.second * slot.height).toFloat(),
                    )
                    before = vm.state.value.sheet.stamps.size
                }
                canvas.performTouchInput {
                    assertTrue("toque $i fora da área visível: $screen em ${width}x$height", screen.y < height && screen.x < width)
                    doubleClick(screen)
                }
                compose.waitForIdle()
                var after = 0
                scenario.onActivity { after = it.appViewModel.state.value.sheet.stamps.size }
                log.append("toque $i em $screen: $before -> $after\n")
            }

            scenario.onActivity { a ->
                val stamps = a.appViewModel.state.value.sheet.stamps
                assertEquals(log.toString(), 10, stamps.size)
                assertEquals(10, stamps.map { it.id }.toSet().size)
                stamps.forEach { assertEquals(stamps.first().widthFraction, it.widthFraction, 1e-9) }
                // cada carimbo nasce no ponto tocado (tolerância de 1% da página)
                for ((s, t) in stamps.zip(targets)) {
                    assertEquals(log.toString(), t.first, s.centerX, 0.01)
                    assertEquals(log.toString(), t.second, s.centerY, 0.01)
                }
            }

            // zoom por pinça não altera os carimbos
            var before: List<Stamp> = emptyList()
            var scaleBefore = 0.0
            scenario.onActivity {
                before = it.appViewModel.state.value.sheet.stamps
                scaleBefore = it.appViewModel.viewport!!.scale
            }
            canvas.performTouchInput {
                pinch(center + Offset(-30f, 0f), center + Offset(-150f, 0f), center + Offset(30f, 0f), center + Offset(150f, 0f))
            }
            compose.waitForIdle()
            scenario.onActivity { a ->
                assertEquals(before, a.appViewModel.state.value.sheet.stamps)
                assertTrue("o zoom deveria ter aumentado", a.appViewModel.viewport!!.scale > scaleBefore)
            }
        }
    }
}
