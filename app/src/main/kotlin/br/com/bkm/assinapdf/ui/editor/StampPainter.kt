package br.com.bkm.assinapdf.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import br.com.bkm.assinapdf.stamps.StampContent
import br.com.bkm.assinapdf.stamps.StampVisual

/**
 * Desenha o carimbo no Canvas do Android no espaço de desenho 300 × 100 (Y para baixo),
 * espelhando o StampPdfWriter. Quem chama aplica translação, rotação e escala.
 */
class StampPainter {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val rect = RectF()
    private val house = toPath(StampVisual.housePath)
    private val logo: Bitmap by lazy {
        val alpha = StampVisual.icpLogoAlpha
        val rgb = StampVisual.VERDE_ICP and 0xFFFFFF
        val pixels = IntArray(alpha.size) { i -> ((alpha[i].toInt() and 0xFF) shl 24) or rgb }
        Bitmap.createBitmap(pixels, StampVisual.LOGO_PIXELS_W, StampVisual.LOGO_PIXELS_H, Bitmap.Config.ARGB_8888)
    }

    fun draw(canvas: Canvas, content: StampContent, whiteBackground: Boolean) {
        val label = StampVisual.label(content)
        val labelSize = fit(label)
        val gapStart = StampVisual.TEXT_LEFT - StampVisual.LABEL_GAP
        val gapEnd = StampVisual.TEXT_LEFT + text.measureText(label.text) + StampVisual.LABEL_GAP

        if (whiteBackground) {
            fill.color = StampVisual.BRANCO
            fill.alpha = (0.85f * 255).toInt()
            val closedAt = StampVisual.BORDER_LEFT + StampVisual.BORDER_RADIUS
            canvas.drawPath(toPath(StampVisual.borderPath(closedAt, closedAt)).apply { close() }, fill)
            fill.alpha = 255
        }
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
        stroke.strokeWidth = StampVisual.BORDER_WIDTH.toFloat()
        stroke.color = StampVisual.MARINHO
        canvas.drawPath(toPath(StampVisual.borderPath(gapStart, gapEnd)), stroke)

        val cx = StampVisual.SEAL_CX.toFloat()
        val cy = StampVisual.SEAL_CY.toFloat()
        val r = StampVisual.SEAL_R.toFloat()
        fill.color = StampVisual.BRANCO
        canvas.drawCircle(cx, cy, r, fill)
        stroke.strokeWidth = StampVisual.SEAL_STROKE.toFloat()
        stroke.color = StampVisual.AZUL_BKM
        canvas.drawCircle(cx, cy, r, stroke)
        fill.color = StampVisual.AZUL_BKM
        canvas.drawPath(house, fill)

        if (content.showIcpLogo) {
            rect.set(
                StampVisual.LOGO_X.toFloat(),
                StampVisual.LOGO_Y.toFloat(),
                (StampVisual.LOGO_X + StampVisual.LOGO_W).toFloat(),
                (StampVisual.LOGO_Y + StampVisual.LOGO_H).toFloat(),
            )
            canvas.drawBitmap(logo, null, rect, bitmapPaint)
        }

        drawLine(canvas, label, labelSize)
        for (line in StampVisual.lines(content)) {
            if (line.text.isBlank()) continue
            drawLine(canvas, line, fit(line))
        }
    }

    /** Ajusta a fonte de [text] para [line] e devolve o tamanho final. */
    private fun fit(line: StampVisual.TextLine): Float {
        text.typeface = if (line.bold) bold else regular
        text.textSize = 100f
        val widthAt1 = text.measureText(line.text) / 100.0
        text.textSize = StampVisual.fittedSize(line, widthAt1).toFloat()
        return text.textSize
    }

    private fun drawLine(canvas: Canvas, line: StampVisual.TextLine, size: Float) {
        text.typeface = if (line.bold) bold else regular
        text.textSize = size
        text.color = line.color
        canvas.drawText(line.text, StampVisual.TEXT_LEFT.toFloat(), line.baseline.toFloat(), text)
    }

    private fun toPath(cmds: List<StampVisual.PathCmd>): Path = Path().apply {
        for (c in cmds) when (c) {
            is StampVisual.PathCmd.MoveTo -> moveTo(c.x.toFloat(), c.y.toFloat())
            is StampVisual.PathCmd.LineTo -> lineTo(c.x.toFloat(), c.y.toFloat())
            is StampVisual.PathCmd.CubicTo -> cubicTo(c.x1.toFloat(), c.y1.toFloat(), c.x2.toFloat(), c.y2.toFloat(), c.x.toFloat(), c.y.toFloat())
            StampVisual.PathCmd.Close -> close()
        }
    }
}
