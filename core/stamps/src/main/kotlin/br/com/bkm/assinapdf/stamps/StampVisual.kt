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

    /** Verde do logo ICP-Brasil, medido no carimbo do SafeID. */
    const val VERDE_ICP = 0xFF179546.toInt()

    // Moldura: o selo fica sobre a borda esquerda e o rótulo interrompe a linha de cima
    const val BORDER_LEFT = 38.0
    const val BORDER_TOP = 8.0
    const val BORDER_RIGHT = 298.0
    const val BORDER_BOTTOM = 98.0
    const val BORDER_WIDTH = 2.0
    const val BORDER_RADIUS = 8.0

    /** Espaço entre o rótulo e as pontas da linha interrompida. */
    const val LABEL_GAP = 4.0

    const val SEAL_CX = 38.0
    const val SEAL_CY = 53.0
    const val SEAL_R = 29.0
    const val SEAL_STROKE = 3.0

    const val TEXT_LEFT = 84.0
    const val NAME_RIGHT = 292.0
    const val TEXT_RIGHT = 254.0

    /** Logo ICP-Brasil à direita, abaixo da linha do nome. */
    const val LOGO_X = 260.0
    const val LOGO_Y = 47.0
    const val LOGO_W = 30.0
    const val LOGO_PIXELS_W = 67
    const val LOGO_PIXELS_H = 81
    val LOGO_H = LOGO_W * LOGO_PIXELS_H / LOGO_PIXELS_W

    /**
     * Cobertura (alfa, 8 bits, linha a linha a partir do topo) do logo ICP-Brasil, recortado do
     * carimbo do SafeID; a cor é [VERDE_ICP].
     */
    val icpLogoAlpha: ByteArray by lazy {
        val stream = StampVisual::class.java.getResourceAsStream("icp_brasil_alfa.bin")
            ?: error("recurso icp_brasil_alfa.bin ausente")
        stream.use { it.readBytes() }.also { require(it.size == LOGO_PIXELS_W * LOGO_PIXELS_H) }
    }

    /** Comandos de caminho no espaço de desenho (Y para baixo). */
    sealed interface PathCmd {
        data class MoveTo(val x: Double, val y: Double) : PathCmd
        data class LineTo(val x: Double, val y: Double) : PathCmd
        data class CubicTo(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : PathCmd
        data object Close : PathCmd
    }

    /** Moldura arredondada com a linha de cima interrompida entre [gapStart] e [gapEnd]. */
    fun borderPath(gapStart: Double, gapEnd: Double): List<PathCmd> {
        val l = BORDER_LEFT
        val t = BORDER_TOP
        val r = BORDER_RIGHT
        val b = BORDER_BOTTOM
        val rad = BORDER_RADIUS
        val k = rad * 0.5523
        return listOf(
            PathCmd.MoveTo(gapEnd, t),
            PathCmd.LineTo(r - rad, t),
            PathCmd.CubicTo(r - rad + k, t, r, t + rad - k, r, t + rad),
            PathCmd.LineTo(r, b - rad),
            PathCmd.CubicTo(r, b - rad + k, r - rad + k, b, r - rad, b),
            PathCmd.LineTo(l + rad, b),
            PathCmd.CubicTo(l + rad - k, b, l, b - rad + k, l, b - rad),
            PathCmd.LineTo(l, t + rad),
            PathCmd.CubicTo(l, t + rad - k, l + rad - k, t, l + rad, t),
            PathCmd.LineTo(gapStart, t),
        )
    }

    /** Símbolo da casa da BKM (o mesmo do app), centralizado no selo. */
    val housePath: List<PathCmd> by lazy {
        // caixa original 185,959 × 200; altura final 34
        val s = 34.0 / 200.0
        val ox = SEAL_CX - 185.959 * s / 2
        val oy = SEAL_CY - 200.0 * s / 2
        fun m(x: Double, y: Double) = PathCmd.MoveTo(ox + x * s, oy + y * s)
        fun l(x: Double, y: Double) = PathCmd.LineTo(ox + x * s, oy + y * s)
        fun c(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) =
            PathCmd.CubicTo(ox + x1 * s, oy + y1 * s, ox + x2 * s, oy + y2 * s, ox + x * s, oy + y * s)
        listOf(
            m(185.942, 173.768), l(185.942, 149.34), l(0.01, 149.34), l(0.01, 173.768),
            c(0.01, 188.231, 10.602, 200.0, 23.618, 200.0), l(162.334, 200.0),
            c(175.35, 200.0, 185.942, 188.231, 185.942, 173.768), PathCmd.Close,
            m(92.991, 62.504), l(91.606, 63.798), l(30.864, 120.559), l(0.01, 150.693), l(0.01, 105.255),
            l(0.017, 105.255), c(0.0, 94.355, 2.714, 85.167, 12.365, 75.462), l(92.702, 0.0), l(125.52, 30.581),
            l(173.594, 75.462), c(183.244, 85.167, 185.959, 94.355, 185.942, 105.255), l(185.942, 150.693),
            l(155.095, 120.559), l(94.353, 63.798), l(92.968, 62.504), PathCmd.Close,
        )
    }

    data class TextLine(
        val text: String,
        val baseline: Double,
        val maxSize: Double,
        val bold: Boolean,
        val color: Int,
        val right: Double = TEXT_RIGHT,
    )

    /** Rótulo sobre a linha de cima da moldura, centrado verticalmente nela. */
    fun label(content: StampContent): TextLine =
        TextLine(content.label, BORDER_TOP + 3.3, 9.0, bold = true, color = TEXTO_DESTAQUE, right = NAME_RIGHT)

    /** Linhas de texto dentro da moldura, na ordem de desenho. Linhas vazias não são desenhadas. */
    fun lines(content: StampContent): List<TextLine> = listOf(
        TextLine(content.holder, 38.0, 17.0, bold = true, color = MARINHO, right = NAME_RIGHT),
        TextLine(content.cpfLine, 53.0, 11.0, bold = false, color = CINZA_ESCURO),
        TextLine(content.dateLine, 67.5, 10.5, bold = false, color = CINZA_ESCURO),
        TextLine(content.footer, 86.0, 9.0, bold = false, color = TEXTO_DESTAQUE),
    )

    /** Tamanho final da fonte para [line] caber até [TextLine.right], dado o avanço medido com tamanho 1. */
    fun fittedSize(line: TextLine, widthAtSize1: Double): Double {
        if (widthAtSize1 <= 0) return line.maxSize
        return minOf(line.maxSize, (line.right - TEXT_LEFT) / widthAtSize1)
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
    val cpfLine: String,
    val dateLine: String,
    val footer: String,
) {
    /** O logo ICP-Brasil só acompanha a assinatura (e a prévia dela), nunca o rascunho. */
    val showIcpLogo: Boolean get() = mode != StampMode.DRAFT

    companion object {
        const val UNKNOWN_HOLDER = "Titular do certificado"
        const val VERIFY_HINT = "Verifique em validar.iti.gov.br"

        fun preview(holderName: String?): StampContent = StampContent(
            mode = StampMode.PREVIEW,
            label = "PRÉVIA · ASSINATURA DIGITAL PENDENTE",
            holder = holderName?.takeIf { it.isNotBlank() } ?: UNKNOWN_HOLDER,
            cpfLine = "CPF: conforme o certificado",
            dateLine = "Data: definida ao assinar",
            footer = VERIFY_HINT,
        )

        fun draft(holderName: String?, generatedAt: String): StampContent = StampContent(
            mode = StampMode.DRAFT,
            label = "RASCUNHO · NÃO ASSINADO DIGITALMENTE",
            holder = holderName?.takeIf { it.isNotBlank() } ?: UNKNOWN_HOLDER,
            cpfLine = "",
            dateLine = "Gerado em $generatedAt",
            footer = "Sem assinatura digital",
        )

        /**
         * Carimbo final: [holderName] e [cpf] vêm do certificado devolvido pelo provedor e
         * [signingTime] é o horário gravado no dicionário de assinatura (/M).
         */
        fun final(holderName: String, cpf: String?, signingTime: String): StampContent = StampContent(
            mode = StampMode.FINAL,
            label = "ASSINADO DIGITALMENTE POR",
            holder = holderName,
            cpfLine = formatCpf(cpf)?.let { "CPF: $it" } ?: "",
            dateLine = "Data: $signingTime",
            footer = VERIFY_HINT,
        )

        /** 05191421604 → 051.914.216-04; nulo se não forem 11 dígitos. */
        fun formatCpf(cpf: String?): String? {
            val d = cpf?.filter { it.isDigit() } ?: return null
            if (d.length != 11) return null
            return "${d.substring(0, 3)}.${d.substring(3, 6)}.${d.substring(6, 9)}-${d.substring(9)}"
        }
    }
}
