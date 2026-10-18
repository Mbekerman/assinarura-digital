package br.com.bkm.assinapdf.ui

import br.com.bkm.assinapdf.data.RecentDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUiStateTest {
    private fun doc(id: String, signed: Boolean = false, dispatched: Boolean = false) =
        RecentDocument(id, "$id.pdf", 0L, signed = signed, dispatchedAtMs = if (dispatched) 1L else null)

    @Test
    fun `abas separam assinados, rascunhos e despachados`() {
        val s = AppUiState(recents = listOf(doc("a", signed = true), doc("r1"), doc("d", signed = true, dispatched = true), doc("r2")))
        assertEquals(listOf("a"), s.docsFor(HomeTab.ASSINADOS).map { it.id })
        assertEquals(listOf("r1", "r2"), s.docsFor(HomeTab.RASCUNHOS).map { it.id })
        assertEquals(listOf("d"), s.docsFor(HomeTab.DESPACHADOS).map { it.id })
    }

    @Test
    fun `proximo rascunho vem depois do documento na lista ou volta ao primeiro`() {
        val s = AppUiState(recents = listOf(doc("r1"), doc("x", signed = true), doc("y", signed = true), doc("r2"), doc("r3")))
        assertEquals("r2", s.nextDraftAfter("x"))
        assertEquals("r3", s.nextDraftAfter("r2"))
        assertEquals("r1", s.nextDraftAfter("r3")) // nenhum depois: o primeiro
        assertEquals("r1", s.nextDraftAfter("desconhecido"))
        assertNull(AppUiState(recents = listOf(doc("x", signed = true))).nextDraftAfter("x"))
        assertNull(AppUiState(recents = listOf(doc("so"))).nextDraftAfter("so")) // ele mesmo não conta
    }
}
