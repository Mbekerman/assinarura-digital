package br.com.bkm.assinapdf.sharing

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
    @Test
    fun `nome do assinado segue NOME_ORIGINAL_assinado`() {
        assertEquals("Planta Baixa A1_assinado.pdf", FileNames.signedName("Planta Baixa A1.pdf"))
        assertEquals("contrato_assinado.pdf", FileNames.signedName("contrato.PDF"))
        assertEquals("contrato_RASCUNHO_nao_assinado.pdf", FileNames.draftName("contrato.pdf"))
    }

    @Test
    fun `remove caracteres invalidos e caminhos`() {
        assertEquals("a_b_c_assinado.pdf", FileNames.signedName("a/b\\c.pdf"))
        assertEquals("documento_assinado.pdf", FileNames.signedName("   .pdf"))
        assertEquals(120, FileNames.baseName("x".repeat(300)).length)
    }
}
