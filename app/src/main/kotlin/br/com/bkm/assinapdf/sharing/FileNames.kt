package br.com.bkm.assinapdf.sharing

/** Nomes de arquivo de saída. Sem dependências Android (testado em app/src/test). */
object FileNames {
    private const val MAX_BASE = 120

    /** Nome sem extensão e sem caracteres problemáticos para sistemas de arquivos e apps de mensagem. */
    fun baseName(original: String): String {
        val noExt = original.trim().let { if (it.lowercase().endsWith(".pdf")) it.dropLast(4) else it }
        val cleaned = noExt
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_')
        return cleaned.take(MAX_BASE).ifEmpty { "documento" }
    }

    fun ensurePdf(name: String): String = baseName(name) + ".pdf"

    /** "NOME_ORIGINAL_assinado.pdf" */
    fun signedName(original: String): String = baseName(original) + "_assinado.pdf"

    /** Rascunho identificado claramente como não assinado. */
    fun draftName(original: String): String = baseName(original) + "_RASCUNHO_nao_assinado.pdf"
}
