package br.com.bkm.assinapdf.stamps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StampEditorTest {
    private val a4 = PageGeometry(0, PdfBox.ofSize(595.28, 841.89))
    private val a1Landscape = PageGeometry(1, PdfBox.ofSize(2383.94, 1683.78))
    private val a0Rotated = PageGeometry(2, PdfBox.ofSize(2383.94, 3370.39), rotation = 90)

    private fun editor() = StampEditor(listOf(a4, a1Landscape, a0Rotated))

    @Test
    fun `insercao multipla cria carimbos independentes com o mesmo percentual em paginas diferentes`() {
        val e = editor()
        repeat(10) { i -> e.addAt(i % 3, 0.1 + i * 0.08, 0.2 + i * 0.05) }
        assertEquals(10, e.sheet.stamps.size)
        assertEquals(10, e.sheet.stamps.map { it.id }.toSet().size)
        e.sheet.stamps.forEach { assertEquals(0.20, it.widthFraction, 1e-9) }
        val s4 = e.sheet.stamps.first { it.pageIndex == 0 }
        val s1 = e.sheet.stamps.first { it.pageIndex == 1 }
        val s0 = e.sheet.stamps.first { it.pageIndex == 2 }
        assertEquals(42.0, s4.widthMm(a4), 0.1)
        assertEquals(168.2, s1.widthMm(a1Landscape), 0.1) // A1 paisagem: largura exibida 841 mm
        assertEquals(237.8, s0.widthMm(a0Rotated), 0.1) // A0 com /Rotate 90: largura exibida 1189 mm
    }

    @Test
    fun `novos carimbos herdam tamanho e rotacao do ultimo configurado`() {
        val e = editor()
        val first = e.addAt(0, 0.5, 0.5)
        e.setRotation(first.id, 90.0)
        e.setWidthFraction(first.id, 0.1)
        val second = e.addAt(1, 0.5, 0.5)
        assertEquals(90.0, second.rotationDeg, 1e-9)
        assertEquals(0.1, second.widthFraction, 1e-9)
        e.resetDefaults()
        val third = e.addAt(0, 0.2, 0.2)
        assertEquals(0.0, third.rotationDeg, 1e-9)
        assertEquals(0.2, third.widthFraction, 1e-9)
    }

    @Test
    fun `gesto de arraste gera um unico passo de historico e atualiza o padrao herdado`() {
        val e = editor()
        val s = e.addAt(0, 0.5, 0.5)
        e.beginGesture()
        repeat(20) { e.move(s.id, 2.0, 1.0) }
        e.endGesture()
        assertTrue(e.canUndo)
        assertTrue(e.undo())
        assertEquals(0.5, e.sheet.stamps.first().centerX, 1e-9)
        assertTrue(e.redo())
        assertTrue(e.sheet.stamps.first().centerX > 0.5)
        assertTrue(e.undo()) // desfaz o movimento
        assertTrue(e.undo()) // desfaz a inserção
        assertTrue(e.sheet.stamps.isEmpty())
        assertFalse(e.undo())
    }

    @Test
    fun `redimensionar pelo canto preserva a proporcao`() {
        val e = editor()
        val s = e.addAt(0, 0.5, 0.5)
        val before = e.sheet.stamps.first()
        val corner = before.cornersPt(a4)[2]
        e.beginGesture()
        e.resizeByCorner(s.id, corner.first + 60.0, corner.second + 20.0)
        e.endGesture()
        val after = e.sheet.stamps.first()
        assertTrue(after.widthFraction > before.widthFraction)
        assertEquals(after.widthPt(a4) / after.heightPt(a4), before.widthPt(a4) / before.heightPt(a4), 1e-9)
        assertEquals(after.widthFraction, e.sheet.lastUsed.widthFraction, 1e-9)
        // reduz
        val c2 = after.cornersPt(a4)[2]
        e.beginGesture()
        e.resizeByCorner(s.id, c2.first - 100.0, c2.second - 30.0)
        e.endGesture()
        assertTrue(e.sheet.stamps.first().widthFraction < after.widthFraction)
    }

    @Test
    fun `rotacao pela alca aplica ajuste magnetico perto de 90 graus`() {
        val e = editor()
        val s = e.addAt(0, 0.5, 0.5)
        val (cx, cy) = s.centerPt(a4)
        // alça arrastada para a direita do centro (ângulo 0 do vetor) => rotação 90°
        e.rotateByHandle(s.id, cx + 100.0, cy + 3.0)
        assertEquals(90.0, e.sheet.stamps.first().rotationDeg, 1e-9)
        // 30° de verdade não gruda
        val rad = Math.toRadians(30.0 - 90.0)
        e.rotateByHandle(s.id, cx + 100 * Math.cos(rad), cy + 100 * Math.sin(rad))
        assertEquals(30.0, e.sheet.stamps.first().rotationDeg, 1e-6)
    }

    @Test
    fun `girar 90 graus, excluir, duplicar e restaurar`() {
        val e = editor()
        val s = e.addAt(0, 0.5, 0.5)
        e.rotate90(s.id, clockwise = true)
        assertEquals(90.0, e.sheet.stamps.first().rotationDeg, 1e-9)
        e.rotate90(s.id, clockwise = false)
        e.rotate90(s.id, clockwise = false)
        assertEquals(270.0, e.sheet.stamps.first().rotationDeg, 1e-9)
        val dup = e.duplicate(s.id)
        assertNotNull(dup)
        assertEquals(2, e.sheet.stamps.size)
        assertEquals(dup!!.id, e.sheet.selectedId)
        assertEquals(270.0, dup.rotationDeg, 1e-9)
        e.resetToFactory(dup.id)
        assertEquals(0.0, e.sheet.stamps.last().rotationDeg, 1e-9)
        e.delete(dup.id)
        assertEquals(1, e.sheet.stamps.size)
        assertNull(e.sheet.selectedId)
    }

    @Test
    fun `carimbo nunca se perde fora da pagina`() {
        val e = editor()
        val s = e.addAt(0, 0.5, 0.5)
        e.beginGesture()
        e.move(s.id, 100000.0, -100000.0)
        e.endGesture()
        val moved = e.sheet.stamps.first()
        assertTrue(moved.centerX <= 1.0 && moved.centerX >= 0.0)
        assertTrue(moved.centerY <= 1.0 && moved.centerY >= 0.0)
        e.fitInsidePage(s.id)
        assertTrue(StampRules.isFullyInside(e.sheet.stamps.first(), a4))
    }

    @Test
    fun `aplicar rotacao de outro carimbo e a todos`() {
        val e = editor()
        val a = e.addAt(0, 0.3, 0.3)
        e.setRotation(a.id, 45.0)
        e.resetDefaults()
        val b = e.addAt(0, 0.6, 0.6)
        assertEquals(0.0, b.rotationDeg, 1e-9)
        e.applyRotationFrom(a.id, b.id)
        assertEquals(45.0, e.sheet.stamps.first { it.id == b.id }.rotationDeg, 1e-9)
        e.setRotation(b.id, 180.0)
        e.applyRotationToAll(b.id)
        assertTrue(e.sheet.stamps.all { it.rotationDeg == 180.0 })
    }

    @Test
    fun `largura exata em milimetros`() {
        val e = editor()
        val s = e.addAt(1, 0.5, 0.5)
        e.setWidthMm(s.id, 100.0)
        assertEquals(100.0, e.sheet.stamps.first().widthMm(a1Landscape), 1e-6)
    }

    @Test
    fun `sessao de edicao serializa e desserializa`() {
        val e = editor()
        e.addAt(0, 0.5, 0.5)
        e.addAt(2, 0.1, 0.9)
        val session = EditingSession(
            documentId = "doc-1", originalName = "planta.pdf", localPath = "/tmp/planta.pdf",
            pages = listOf(a4, a1Landscape, a0Rotated), sheet = e.sheet, currentPage = 2, addMode = true,
        )
        val restored = EditingSession.fromJson(session.toJson())
        assertEquals(session, restored)
        assertEquals(90, restored.pages[2].rotation)
    }

    @Test
    fun `hit test escolhe o carimbo de cima e as alcas`() {
        val e = editor()
        val a = e.addAt(0, 0.5, 0.5)
        val b = e.addAt(0, 0.5, 0.5)
        val (cx, cy) = a.centerPt(a4)
        val hit = StampInteraction.hitStamp(e.sheet.stamps, a4, cx, cy, 0.0)
        assertEquals(b.id, hit?.id)
        val corner = b.cornersPt(a4)[1]
        val handle = StampInteraction.hitHandle(b, a4, corner.first + 1.0, corner.second - 1.0, radiusPt = 10.0, rotateOffsetPt = 30.0)
        assertEquals(Handle.TOP_RIGHT, handle)
        val rot = StampInteraction.handlePositions(b, a4, 30.0)[Handle.ROTATE]!!
        assertEquals(Handle.ROTATE, StampInteraction.hitHandle(b, a4, rot.first, rot.second, 10.0, 30.0))
        assertNull(StampInteraction.hitHandle(b, a4, cx, cy, 10.0, 30.0))
    }

    @Test
    fun `viewport ajusta largura e pagina e limita deslocamento`() {
        val v = Viewport.fitWidth(a4, 1080.0, 1920.0)
        assertEquals(1080.0, a4.displayWidthPt * v.scale, 1e-6)
        val vp = Viewport.fitPage(a1Landscape, 1080.0, 1920.0)
        assertTrue(a1Landscape.displayWidthPt * vp.scale <= 1080.0 + 1e-6)
        assertTrue(a1Landscape.displayHeightPt * vp.scale <= 1920.0 + 1e-6)
        val zoomed = v.zoomedAround(4.0, 540.0, 960.0, 0.1, 50.0).panned(5000.0, -9000.0).clampedTo(a4, 1080.0, 1920.0)
        assertTrue(zoomed.offsetX <= 0.0)
        assertTrue(zoomed.offsetY >= 1920.0 - a4.displayHeightPt * zoomed.scale)
    }
}
