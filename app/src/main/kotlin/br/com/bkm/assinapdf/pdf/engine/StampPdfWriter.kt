package br.com.bkm.assinapdf.pdf.engine

import br.com.bkm.assinapdf.stamps.PageGeometry
import br.com.bkm.assinapdf.stamps.Stamp
import br.com.bkm.assinapdf.stamps.StampContent
import br.com.bkm.assinapdf.stamps.StampPlacement
import br.com.bkm.assinapdf.stamps.StampVisual
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.shading.PDShadingType2
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.DeflaterOutputStream

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
 *   pelas assinaturas existentes. Só serve para rascunho: o validador do ITI trata anotações
 *   novas como modificação visual e deixa as assinaturas anteriores "Indeterminadas". Ao
 *   assinar um PDF já assinado, os carimbos vão na aparência da própria assinatura
 *   ([PdfSigner] com [VisibleStamps]).
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
                val placed = placedAppearance(doc, stamp, geometry, form)
                val annot = PDAnnotationRubberStamp()
                annot.rectangle = placed.rect
                annot.contents = content.label + " " + content.holder
                annot.cosObject.setInt(COSName.F, FLAG_PRINT or FLAG_LOCKED or FLAG_LOCKED_CONTENTS)
                annot.appearance = PDAppearanceDictionary().also { it.setNormalAppearance(placed.appearance) }
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

    /** Retângulo na página e aparência de um carimbo, para anotação ou widget de assinatura. */
    class PlacedAppearance(val rect: PDRectangle, val appearance: PDAppearanceStream)

    fun placedAppearance(doc: PDDocument, stamp: Stamp, geometry: PageGeometry, form: PDFormXObject): PlacedAppearance {
        val m = StampPlacement.place(stamp, geometry).matrix
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
        return PlacedAppearance(rect, ap)
    }

    /** Cria o Form XObject do carimbo (BBox 0 0 300 100, Y para cima). */
    fun createForm(doc: PDDocument, content: StampContent, style: StampStyle): PDFormXObject {
        val form = PDFormXObject(doc)
        form.bBox = PDRectangle(0f, 0f, StampVisual.W.toFloat(), StampVisual.H.toFloat())
        form.resources = PDResources()
        PDPageContentStream(doc, form, form.contentStream.createOutputStream(COSName.FLATE_DECODE)).use { cs ->
            drawStamp(doc, cs, content, style)
        }
        return form
    }

    private fun drawStamp(doc: PDDocument, cs: PDPageContentStream, content: StampContent, style: StampStyle) {
        val h = StampVisual.H
        fun y(v: Double) = (h - v).toFloat()
        fun f(v: Double) = v.toFloat()

        // Rótulo medido antes: a linha de cima da moldura é interrompida em volta dele
        val label = StampVisual.label(content)
        val labelText = StampVisual.winAnsiSafe(label.text)
        val labelFont: PDFont = PDType1Font.HELVETICA_BOLD
        val labelAt1 = labelFont.getStringWidth(labelText) / 1000.0
        val labelSize = StampVisual.fittedSize(label, labelAt1)
        val gapStart = StampVisual.TEXT_LEFT - StampVisual.LABEL_GAP
        val gapEnd = StampVisual.TEXT_LEFT + labelAt1 * labelSize + StampVisual.LABEL_GAP

        if (style.whiteBackground) {
            cs.saveGraphicsState()
            val gs = PDExtendedGraphicsState()
            gs.nonStrokingAlphaConstant = style.backgroundAlpha
            cs.setGraphicsStateParameters(gs)
            color(cs, StampVisual.BRANCO, stroke = false)
            path(cs, StampVisual.borderPath(StampVisual.BORDER_LEFT + StampVisual.BORDER_RADIUS, StampVisual.BORDER_LEFT + StampVisual.BORDER_RADIUS))
            cs.closePath()
            cs.fill()
            cs.restoreGraphicsState()
        }

        // Moldura
        cs.setLineWidth(StampVisual.BORDER_WIDTH.toFloat())
        cs.setLineCapStyle(0)
        color(cs, StampVisual.MARINHO, stroke = true)
        path(cs, StampVisual.borderPath(gapStart, gapEnd))
        cs.stroke()

        // Selo: círculo branco sobre a borda esquerda, anel azul e a casa da BKM
        color(cs, StampVisual.BRANCO, stroke = false)
        circle(cs, StampVisual.SEAL_CX, h - StampVisual.SEAL_CY, StampVisual.SEAL_R)
        cs.fill()
        cs.setLineWidth(StampVisual.SEAL_STROKE.toFloat())
        color(cs, StampVisual.AZUL_BKM, stroke = true)
        circle(cs, StampVisual.SEAL_CX, h - StampVisual.SEAL_CY, StampVisual.SEAL_R)
        cs.stroke()
        // casa com o degradê da marca: sombreamento axial recortado no contorno
        cs.saveGraphicsState()
        path(cs, StampVisual.housePath)
        cs.clip()
        cs.shadingFill(houseGradient(y(StampVisual.SEAL_CY)))
        cs.restoreGraphicsState()

        // Logo ICP-Brasil (imagem com máscara de transparência, sem perdas)
        if (content.showIcpLogo) {
            val logo = icpLogo(doc)
            cs.drawImage(logo, f(StampVisual.LOGO_X), y(StampVisual.LOGO_Y + StampVisual.LOGO_H), f(StampVisual.LOGO_W), f(StampVisual.LOGO_H))
        }

        // Texto (Helvetica padrão, WinAnsi, vetorial)
        for (line in listOf(label) + StampVisual.lines(content)) {
            val text = StampVisual.winAnsiSafe(line.text)
            if (text.isBlank()) continue
            val font: PDFont = if (line.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
            val widthAt1 = font.getStringWidth(text) / 1000.0
            val size = StampVisual.fittedSize(line, widthAt1).toFloat()
            color(cs, line.color, stroke = false)
            cs.beginText()
            cs.setFont(font, size)
            cs.newLineAtOffset(StampVisual.TEXT_LEFT.toFloat(), y(line.baseline))
            cs.showText(text)
            cs.endText()
        }
    }

    private fun path(cs: PDPageContentStream, cmds: List<StampVisual.PathCmd>) {
        val h = StampVisual.H
        fun x(v: Double) = v.toFloat()
        fun y(v: Double) = (h - v).toFloat()
        for (c in cmds) when (c) {
            is StampVisual.PathCmd.MoveTo -> cs.moveTo(x(c.x), y(c.y))
            is StampVisual.PathCmd.LineTo -> cs.lineTo(x(c.x), y(c.y))
            is StampVisual.PathCmd.CubicTo -> cs.curveTo(x(c.x1), y(c.y1), x(c.x2), y(c.y2), x(c.x), y(c.y))
            StampVisual.PathCmd.Close -> cs.closePath()
        }
    }

    /** Degradê horizontal da casa: função de costura com um trecho linear entre cada par de cores. */
    private fun houseGradient(y: Float): PDShadingType2 {
        fun rgb(argb: Int) = COSArray().also { a ->
            a.add(COSFloat(((argb shr 16) and 0xFF) / 255f))
            a.add(COSFloat(((argb shr 8) and 0xFF) / 255f))
            a.add(COSFloat((argb and 0xFF) / 255f))
        }
        fun floats(vararg v: Float) = COSArray().also { a -> v.forEach { a.add(COSFloat(it)) } }
        val colors = StampVisual.HOUSE_GRADIENT_COLORS
        val stops = StampVisual.HOUSE_GRADIENT_STOPS
        val pieces = COSArray()
        val encode = COSArray()
        for (i in 0 until colors.size - 1) {
            pieces.add(COSDictionary().apply {
                setInt(COSName.FUNCTION_TYPE, 2)
                setItem(COSName.DOMAIN, floats(0f, 1f))
                setItem(COSName.C0, rgb(colors[i]))
                setItem(COSName.C1, rgb(colors[i + 1]))
                setInt(COSName.N, 1)
            })
            encode.add(COSFloat(0f))
            encode.add(COSFloat(1f))
        }
        val stitching = COSDictionary().apply {
            setInt(COSName.FUNCTION_TYPE, 3)
            setItem(COSName.DOMAIN, floats(0f, 1f))
            setItem(COSName.FUNCTIONS, pieces)
            setItem(COSName.BOUNDS, floats(*stops.drop(1).dropLast(1).map { it.toFloat() }.toFloatArray()))
            setItem(COSName.ENCODE, encode)
        }
        val shading = COSDictionary().apply {
            setInt(COSName.SHADING_TYPE, 2)
            setItem(COSName.COLORSPACE, COSName.DEVICERGB)
            setItem(COSName.COORDS, floats(StampVisual.HOUSE_LEFT.toFloat(), y, StampVisual.HOUSE_RIGHT.toFloat(), y))
            setItem(COSName.FUNCTION, stitching)
            setItem(COSName.EXTEND, COSArray().also { it.add(COSBoolean.TRUE); it.add(COSBoolean.TRUE) })
        }
        return PDShadingType2(shading)
    }

    /** Logo ICP-Brasil: cor sólida com a cobertura como /SMask. */
    private fun icpLogo(doc: PDDocument): PDImageXObject {
        val w = StampVisual.LOGO_PIXELS_W
        val h = StampVisual.LOGO_PIXELS_H
        val c = StampVisual.VERDE_ICP
        val rgb = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            rgb[i * 3] = (c shr 16).toByte()
            rgb[i * 3 + 1] = (c shr 8).toByte()
            rgb[i * 3 + 2] = c.toByte()
        }
        val image = PDImageXObject(doc, ByteArrayInputStream(deflate(rgb)), COSName.FLATE_DECODE, w, h, 8, PDDeviceRGB.INSTANCE)
        val mask = PDImageXObject(doc, ByteArrayInputStream(deflate(StampVisual.icpLogoAlpha)), COSName.FLATE_DECODE, w, h, 8, PDDeviceGray.INSTANCE)
        image.cosObject.setItem(COSName.SMASK, mask.cosObject)
        return image
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun color(cs: PDPageContentStream, argb: Int, stroke: Boolean) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        if (stroke) cs.setStrokingColor(r, g, b) else cs.setNonStrokingColor(r, g, b)
    }

    private const val K = 0.5522847498

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

    internal const val FLAG_PRINT = 1 shl 2
    internal const val FLAG_LOCKED = 1 shl 7
    private const val FLAG_LOCKED_CONTENTS = 1 shl 9
}
