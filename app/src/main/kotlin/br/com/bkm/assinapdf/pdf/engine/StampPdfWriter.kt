package br.com.bkm.assinapdf.pdf.engine

import br.com.bkm.assinapdf.stamps.PageGeometry
import br.com.bkm.assinapdf.stamps.Stamp
import br.com.bkm.assinapdf.stamps.StampContent
import br.com.bkm.assinapdf.stamps.StampMode
import br.com.bkm.assinapdf.stamps.StampPlacement
import br.com.bkm.assinapdf.stamps.StampVisual
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream

/** Opções de aparência do carimbo no PDF. */
data class StampStyle(
    /** Fundo branco semitransparente atrás do carimbo (melhora a leitura sobre desenhos). */
    val whiteBackground: Boolean = false,
    val backgroundAlpha: Float = 0.85f,
)

/**
 * Incorpora os carimbos ao PDF preservando o conteúdo original.
 *
 * - Documento sem assinaturas: os carimbos entram no conteúdo das páginas como Form XObjects
 *   vetoriais (nada é rasterizado) e o arquivo é regravado por inteiro.
 * - Documento já assinado: as páginas não são tocadas. Os carimbos entram como anotações de
 *   carimbo travadas, gravadas em atualização incremental, para não alterar os bytes cobertos
 *   pelas assinaturas existentes.
 */
object StampPdfWriter {

    fun write(
        input: File,
        output: File,
        stamps: List<Stamp>,
        content: StampContent,
        style: StampStyle = StampStyle(),
    ) {
        require(input.canonicalPath != output.canonicalPath) { "a saída não pode sobrescrever o original" }
        PdfInspector.open(input).use { doc ->
            val info = PdfInspector.inspect(doc)
            if (stamps.isNotEmpty()) info.stampBlocker()?.let { throw PdfOpenException(it) }
            if (info.hasSignatures) {
                val changed = addAsAnnotations(doc, info.pages, stamps, content, style)
                // atualização incremental: o pdfbox copia o original byte a byte e acrescenta só
                // as páginas alteradas e os objetos novos (anotações e aparências)
                FileOutputStream(output).use { out -> doc.saveIncremental(out, changed) }
            } else {
                addToPageContent(doc, info.pages, stamps, content, style)
                doc.save(output)
            }
        }
    }

    private fun addToPageContent(doc: PDDocument, pages: List<PageGeometry>, stamps: List<Stamp>, content: StampContent, style: StampStyle) {
        val form = createForm(doc, content, style)
        for ((pageIndex, onPage) in stamps.groupBy { it.pageIndex }) {
            val page = doc.getPage(pageIndex)
            val geometry = pages[pageIndex]
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                for (stamp in onPage) {
                    val m = StampPlacement.place(stamp, geometry).matrix
                    cs.saveGraphicsState()
                    cs.transform(Matrix(m[0].toFloat(), m[1].toFloat(), m[2].toFloat(), m[3].toFloat(), m[4].toFloat(), m[5].toFloat()))
                    cs.drawForm(form)
                    cs.restoreGraphicsState()
                }
            }
        }
    }

    private fun addAsAnnotations(doc: PDDocument, pages: List<PageGeometry>, stamps: List<Stamp>, content: StampContent, style: StampStyle): Set<COSDictionary> {
        val changed = HashSet<COSDictionary>()
        if (stamps.isEmpty()) return changed
        val form = createForm(doc, content, style)
        for ((pageIndex, onPage) in stamps.groupBy { it.pageIndex }) {
            val page = doc.getPage(pageIndex)
            val geometry = pages[pageIndex]
            val annotations = page.annotations
            for (stamp in onPage) {
                val placement = StampPlacement.place(stamp, geometry)
                val m = placement.matrix
                val corners = listOf(0.0 to 0.0, StampVisual.W to 0.0, StampVisual.W to StampVisual.H, 0.0 to StampVisual.H)
                    .map { (x, y) -> StampPlacement.apply(m, x, y) }
                val rect = PDRectangle(
                    corners.minOf { it.first }.toFloat(),
                    corners.minOf { it.second }.toFloat(),
                    (corners.maxOf { it.first } - corners.minOf { it.first }).toFloat(),
                    (corners.maxOf { it.second } - corners.minOf { it.second }).toFloat(),
                )
                val ap = PDAppearanceStream(doc)
                ap.bBox = PDRectangle(0f, 0f, StampVisual.W.toFloat(), StampVisual.H.toFloat())
                ap.resources = PDResources()
                // /Matrix com rotação e escala; o leitor encaixa a caixa transformada em /Rect por translação
                ap.cosObject.setItem(COSName.MATRIX, floatArray(m[0], m[1], m[2], m[3], 0.0, 0.0))
                PDPageContentStream(doc, ap).use { cs -> cs.drawForm(form) }
                val annot = PDAnnotationRubberStamp()
                annot.rectangle = rect
                annot.contents = content.label + " " + content.holder
                annot.cosObject.setInt(COSName.F, FLAG_PRINT or FLAG_LOCKED or FLAG_LOCKED_CONTENTS)
                annot.appearance = PDAppearanceDictionary().also { it.setNormalAppearance(ap) }
                annot.page = page
                annotations.add(annot)
            }
            // /Annots direto na página, marcada para gravação incremental
            page.annotations = annotations
            page.cosObject.setNeedToBeUpdated(true)
            changed += page.cosObject
        }
        return changed
    }

    /** Cria o Form XObject do carimbo (BBox 0 0 300 100, Y para cima). */
    fun createForm(doc: PDDocument, content: StampContent, style: StampStyle): PDFormXObject {
        val form = PDFormXObject(doc)
        form.bBox = PDRectangle(0f, 0f, StampVisual.W.toFloat(), StampVisual.H.toFloat())
        form.resources = PDResources()
        PDPageContentStream(doc, form, form.contentStream.createOutputStream(COSName.FLATE_DECODE)).use { cs ->
            drawStamp(cs, content, style)
        }
        return form
    }

    private fun drawStamp(cs: PDPageContentStream, content: StampContent, style: StampStyle) {
        val h = StampVisual.H
        fun y(v: Double) = (h - v).toFloat()

        if (style.whiteBackground) {
            cs.saveGraphicsState()
            val gs = PDExtendedGraphicsState()
            gs.nonStrokingAlphaConstant = style.backgroundAlpha
            cs.setGraphicsStateParameters(gs)
            color(cs, StampVisual.BRANCO, stroke = false)
            roundedRect(cs, StampVisual.BORDER_INSET, StampVisual.BORDER_INSET, StampVisual.W - 2 * StampVisual.BORDER_INSET, h - 2 * StampVisual.BORDER_INSET, StampVisual.BORDER_RADIUS)
            cs.fill()
            cs.restoreGraphicsState()
        }

        // Moldura
        cs.setLineWidth(StampVisual.BORDER_WIDTH.toFloat())
        color(cs, StampVisual.MARINHO, stroke = true)
        roundedRect(cs, StampVisual.BORDER_INSET, StampVisual.BORDER_INSET, StampVisual.W - 2 * StampVisual.BORDER_INSET, h - 2 * StampVisual.BORDER_INSET, StampVisual.BORDER_RADIUS)
        cs.stroke()

        // Selo
        cs.setLineWidth(StampVisual.SEAL_STROKE.toFloat())
        color(cs, StampVisual.AZUL_BKM, stroke = true)
        circle(cs, StampVisual.SEAL_CX, h - StampVisual.SEAL_CY, StampVisual.SEAL_R)
        cs.stroke()
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        cs.setLineWidth(StampVisual.CHECK_STROKE.toFloat())
        val mark = if (content.mode == StampMode.FINAL) StampVisual.CHECK_PATH else StampVisual.PENDING_PATH
        cs.moveTo(mark[0].first.toFloat(), y(mark[0].second))
        for (p in mark.drop(1)) cs.lineTo(p.first.toFloat(), y(p.second))
        cs.stroke()

        // Texto (Helvetica padrão, WinAnsi, vetorial)
        for (line in StampVisual.lines(content)) {
            val text = StampVisual.winAnsiSafe(line.text)
            if (text.isBlank()) continue
            val font: PDFont = if (line.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
            val widthAt1 = font.getStringWidth(text) / 1000.0
            val size = StampVisual.fittedSize(line.maxSize, widthAt1).toFloat()
            color(cs, line.color, stroke = false)
            cs.beginText()
            cs.setFont(font, size)
            cs.newLineAtOffset(StampVisual.TEXT_LEFT.toFloat(), y(line.baseline))
            cs.showText(text)
            cs.endText()
        }
    }

    private fun color(cs: PDPageContentStream, argb: Int, stroke: Boolean) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        if (stroke) cs.setStrokingColor(r, g, b) else cs.setNonStrokingColor(r, g, b)
    }

    private const val K = 0.5522847498

    private fun roundedRect(cs: PDPageContentStream, x: Double, y: Double, w: Double, h: Double, r: Double) {
        val k = r * K
        fun f(v: Double) = v.toFloat()
        cs.moveTo(f(x + r), f(y))
        cs.lineTo(f(x + w - r), f(y))
        cs.curveTo(f(x + w - r + k), f(y), f(x + w), f(y + r - k), f(x + w), f(y + r))
        cs.lineTo(f(x + w), f(y + h - r))
        cs.curveTo(f(x + w), f(y + h - r + k), f(x + w - r + k), f(y + h), f(x + w - r), f(y + h))
        cs.lineTo(f(x + r), f(y + h))
        cs.curveTo(f(x + r - k), f(y + h), f(x), f(y + h - r + k), f(x), f(y + h - r))
        cs.lineTo(f(x), f(y + r))
        cs.curveTo(f(x), f(y + r - k), f(x + r - k), f(y), f(x + r), f(y))
        cs.closePath()
    }

    private fun circle(cs: PDPageContentStream, cx: Double, cy: Double, r: Double) {
        val k = r * K
        fun f(v: Double) = v.toFloat()
        cs.moveTo(f(cx + r), f(cy))
        cs.curveTo(f(cx + r), f(cy + k), f(cx + k), f(cy + r), f(cx), f(cy + r))
        cs.curveTo(f(cx - k), f(cy + r), f(cx - r), f(cy + k), f(cx - r), f(cy))
        cs.curveTo(f(cx - r), f(cy - k), f(cx - k), f(cy - r), f(cx), f(cy - r))
        cs.curveTo(f(cx + k), f(cy - r), f(cx + r), f(cy - k), f(cx + r), f(cy))
        cs.closePath()
    }

    private fun floatArray(vararg values: Double): COSArray = COSArray().also { arr ->
        values.forEach { arr.add(COSFloat(it.toFloat())) }
    }

    private const val FLAG_PRINT = 1 shl 2
    private const val FLAG_LOCKED = 1 shl 7
    private const val FLAG_LOCKED_CONTENTS = 1 shl 9
}
