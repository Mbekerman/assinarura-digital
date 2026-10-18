package br.com.bkm.assinapdf.stamps

import java.text.Normalizer

/**
 * Desenho do carimbo, compartilhado entre a pré-visualização na tela e o PDF exportado.
 *
 * Coordenadas no espaço de desenho [0, DESIGN_WIDTH] × [0, DESIGN_HEIGHT], origem no canto
 * superior esquerdo e Y para baixo. O escritor de PDF inverte o eixo Y; a tela desenha direto.
 * Cada renderizador mede o texto com a própria fonte e reduz o tamanho até caber em [maxWidth],
 * então a composição é a mesma nos dois lados.
 */
object StampVisual {
    const val W = StampPlacement.DESIGN_WIDTH
    const val H = StampPlacement.DESIGN_HEIGHT

    // Cores do design system BKM (tokens.json)
    const val MARINHO = 0xFF1D2E5C.toInt()
    const val AZUL_BKM = 0xFF0094FE.toInt()
    const val TEXTO_DESTAQUE = 0xFF1459C3.toInt() // gradiente-profundo, 6,5:1 sobre branco
    const val CINZA_ESCURO = 0xFF43464B.toInt()
    const val BRANCO = 0xFFFFFFFF.toInt()

    const val BORDER_INSET = 1.5
    const val BORDER_WIDTH = 2.0
    const val BORDER_RADIUS = 10.0

    const val SEAL_CX = 48.0
    const val SEAL_CY = 50.0
    const val SEAL_R = 28.0
    const val SEAL_STROKE = 4.0

    /** Marca de verificação dentro do selo (polilinha). */
    val CHECK_PATH = listOf(35.0 to 51.0, 45.0 to 61.0, 63.0 to 40.0)
    const val CHECK_STROKE = 6.0

    /** Traço diagonal usado no selo de prévia/rascunho em vez da marca de verificação. */
    val PENDING_PATH = listOf(38.0 to 50.0, 58.0 to 50.0)

    const val TEXT_LEFT = 90.0
    const val TEXT_RIGHT = 290.0
    val TEXT_MAX_WIDTH = TEXT_RIGHT - TEXT_LEFT

    data class TextLine(
        val text: String,
        val baseline: Double,
        val maxSize: Double,
        val bold: Boolean,
        val color: Int,
    )

    /** Linhas de texto do carimbo, na ordem de desenho. */
    fun lines(content: StampContent): List<TextLine> = listOf(
        TextLine(content.label, 26.0, 10.5, bold = true, color = TEXTO_DESTAQUE),
        TextLine(content.holder, 50.0, 19.0, bold = true, color = MARINHO),
        TextLine(content.dateLine, 70.0, 11.0, bold = false, color = CINZA_ESCURO),
        TextLine(content.certLine, 87.0, 10.0, bold = false, color = CINZA_ESCURO),
    )

    /** Tamanho final da fonte para [text] caber em [TEXT_MAX_WIDTH], dado o avanço medido com tamanho 1. */
    fun fittedSize(maxSize: Double, widthAtSize1: Double): Double {
        if (widthAtSize1 <= 0) return maxSize
        return minOf(maxSize, TEXT_MAX_WIDTH / widthAtSize1)
    }

    /**
     * Remove caracteres que a codificação WinAnsi (fontes padrão Helvetica do PDF) não representa,
     * preservando acentos do português. Letras fora do conjunto viram a forma sem acento.
     */
    fun winAnsiSafe(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            when {
                ch.code in 0x20..0x7E -> sb.append(ch)
                ch.code in 0xA0..0xFF -> sb.append(ch)
                ch == '·' || ch == '•' -> sb.append('·')
                ch == '–' || ch == '—' -> sb.append('-')
                else -> {
                    val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).filter { it.code in 0x20..0x7E }
                    sb.append(if (base.isEmpty()) "?" else base)
                }
            }
        }
        return sb.toString()
    }
}

/** Modo do carimbo: define o que o texto pode afirmar. */
enum class StampMode {
    /** Na tela, antes de assinar. Nunca afirma que a assinatura foi feita. */
    PREVIEW,

    /** PDF de teste exportado sem assinatura digital. */
    DRAFT,

    /** PDF que vai receber a assinatura digital, com dados do certificado e o horário da assinatura. */
    FINAL,
}

/** Texto do carimbo. Use as fábricas para não afirmar nada que não esteja confirmado. */
data class StampContent(
    val mode: StampMode,
    val label: String,
    val holder: String,
    val dateLine: String,
    val certLine: String,
) {
    companion object {
        const val UNKNOWN_HOLDER = "Titular do certificado"

        fun preview(holderName: String?): StampContent = StampContent(
            mode = StampMode.PREVIEW,
            label = "PRÉVIA · ASSINATURA DIGITAL PENDENTE",
            holder = holderName?.takeIf { it.isNotBlank() } ?: UNKNOWN_HOLDER,
            dateLine = "Data e hora: definidas ao assinar",
            certLine = "Certificado ICP-Brasil",
        )

        fun draft(holderName: String?, generatedAt: String): StampContent = StampContent(
            mode = StampMode.DRAFT,
            label = "RASCUNHO · NÃO ASSINADO DIGITALMENTE",
            holder = holderName?.takeIf { it.isNotBlank() } ?: UNKNOWN_HOLDER,
            dateLine = "Gerado em $generatedAt",
            certLine = "Sem assinatura digital",
        )

        /**
         * Carimbo final: [holderName] e [issuer] vêm do certificado devolvido pelo provedor e
         * [signingTime] é o horário gravado no dicionário de assinatura (/M).
         */
        fun final(holderName: String, signingTime: String, issuer: String?): StampContent = StampContent(
            mode = StampMode.FINAL,
            label = "ASSINADO DIGITALMENTE POR",
            holder = holderName,
            dateLine = "Data: $signingTime",
            certLine = if (issuer.isNullOrBlank()) "Certificado ICP-Brasil" else "ICP-Brasil · $issuer",
        )
    }
}
