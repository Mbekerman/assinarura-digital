package br.com.bkm.assinapdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File

/** Gera PDFs de teste no aparelho (pasta coberta pelo FileProvider do app). */
object TestDocuments {
    /** Tamanho em pontos: A4 = 595 × 842; A1 paisagem = 2384 × 1684. */
    fun create(context: Context, name: String, widthPt: Int = 595, heightPt: Int = 842, pages: Int = 1): File {
        val dir = File(context.cacheDir, "shared").also { it.mkdirs() }
        val file = File(dir, name)
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.GRAY; strokeWidth = 0.5f }
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(widthPt, heightPt, i + 1).create())
            var x = 0f
            while (x < widthPt) { page.canvas.drawLine(x, 0f, x, heightPt.toFloat(), paint); x += 28.35f }
            var y = 0f
            while (y < heightPt) { page.canvas.drawLine(0f, y, widthPt.toFloat(), y, paint); y += 28.35f }
            page.canvas.drawText("PAGINA ${i + 1}", 40f, 60f, Paint().apply { textSize = 24f })
            doc.finishPage(page)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
