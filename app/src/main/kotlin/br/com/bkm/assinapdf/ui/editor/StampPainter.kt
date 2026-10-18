package br.com.bkm.assinapdf.ui.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import br.com.bkm.assinapdf.stamps.StampContent
import br.com.bkm.assinapdf.stamps.StampMode
import br.com.bkm.assinapdf.stamps.StampVisual

/**
 * Desenha o carimbo no Canvas do Android no espaço de desenho 300 × 100 (Y para baixo),
 * espelhando o StampPdfWriter. Quem chama aplica translação, rotação e escala.
 */
class StampPainter {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val rect = RectF()
    private val path = Path()

    fun draw(canvas: Canvas, content: StampContent, whiteBackground: Boolean) {
        val inset = StampVisual.BORDER_INSET.toFloat()
        val r = StampVisual.BORDER_RADIUS.toFloat()
        rect.set(inset, inset, (StampVisual.W - inset).toFloat(), (StampVisual.H - inset).toFloat())
        if (whiteBackground) {
            fill.color = StampVisual.BRANCO
            fill.alpha = (0.85f * 255).toInt()
            canvas.drawRoundRect(rect, r, r, fill)
            fill.alpha = 255
        }
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
        stroke.strokeWidth = StampVisual.BORDER_WIDTH.toFloat()
        stroke.color = StampVisual.MARINHO
        canvas.drawRoundRect(rect, r, r, stroke)

        stroke.strokeWidth = StampVisual.SEAL_STROKE.toFloat()
        stroke.color = StampVisual.AZUL_BKM
        canvas.drawCircle(StampVisual.SEAL_CX.toFloat(), StampVisual.SEAL_CY.toFloat(), StampVisual.SEAL_R.toFloat(), stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.strokeWidth = StampVisual.CHECK_STROKE.toFloat()
        val mark = if (content.mode == StampMode.FINAL) StampVisual.CHECK_PATH else StampVisual.PENDING_PATH
        path.reset()
        path.moveTo(mark[0].first.toFloat(), mark[0].second.toFloat())
        for (p in mark.drop(1)) path.lineTo(p.first.toFloat(), p.second.toFloat())
        canvas.drawPath(path, stroke)

        for (line in StampVisual.lines(content)) {
            if (line.text.isBlank()) continue
            text.typeface = if (line.bold) bold else regular
            text.textSize = 100f
            val widthAt1 = text.measureText(line.text) / 100.0
            text.textSize = StampVisual.fittedSize(line.maxSize, widthAt1).toFloat()
            text.color = line.color
            canvas.drawText(line.text, StampVisual.TEXT_LEFT.toFloat(), line.baseline.toFloat(), text)
        }
    }
}
