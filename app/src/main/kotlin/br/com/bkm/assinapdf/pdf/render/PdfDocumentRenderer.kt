package br.com.bkm.assinapdf.pdf.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import br.com.bkm.assinapdf.stamps.PageGeometry
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Renderização de páginas com o PdfRenderer do Android, fora da thread principal.
 *
 * - Cada página tem uma imagem base de resolução limitada ([BASE_MAX_PX] no maior lado).
 * - Quando o zoom passa da resolução base, só os blocos (tiles) visíveis são renderizados, em
 *   níveis de resolução potência de 2. Assim uma planta A0 nunca vira um bitmap gigante.
 * - O cache é um LRU limitado em bytes; bitmaps descartados são liberados pelo coletor.
 *
 * O PdfRenderer não é thread-safe e só abre uma página por vez: todo acesso passa por uma única
 * thread dedicada.
 */
class PdfDocumentRenderer(file: File, val pages: List<PageGeometry>) : Closeable {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    @Volatile private var closed = false

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int get() = renderer.pageCount

    /** Pixels por ponto da imagem base da página. */
    fun basePxPerPt(index: Int): Double {
        val g = pages[index]
        return min(BASE_MAX_PX / max(g.displayWidthPt, g.displayHeightPt), BASE_MAX_PX_PER_PT)
    }

    /** Nível de resolução (px por pt) dos tiles para a escala de tela atual, ou null se a base basta. */
    fun tileLevel(index: Int, screenPxPerPt: Double): Double? {
        val base = basePxPerPt(index)
        if (screenPxPerPt <= base * 1.15) return null
        val k = ceil(ln(screenPxPerPt / base) / ln(2.0))
        return min(base * 2.0.pow(k), MAX_PX_PER_PT)
    }

    fun baseKey(index: Int) = "p$index-base"
    fun tileKey(index: Int, level: Double, tx: Int, ty: Int) = "p$index-l${(level * 1000).roundToInt()}-$tx-$ty"

    fun cached(key: String): Bitmap? = cache.get(key)

    suspend fun renderBase(index: Int): Bitmap? {
        cache.get(baseKey(index))?.let { return it }
        return renderInto(baseKey(index), index, basePxPerPt(index), null)
    }

    suspend fun renderTile(index: Int, level: Double, tx: Int, ty: Int): Bitmap? {
        val key = tileKey(index, level, tx, ty)
        cache.get(key)?.let { return it }
        val g = pages[index]
        val fullW = (g.displayWidthPt * level).roundToInt()
        val fullH = (g.displayHeightPt * level).roundToInt()
        val left = tx * TILE_PX
        val top = ty * TILE_PX
        if (left >= fullW || top >= fullH) return null
        val rect = Rect(left, top, min(left + TILE_PX, fullW), min(top + TILE_PX, fullH))
        return renderInto(key, index, level, rect)
    }

    private suspend fun renderInto(key: String, index: Int, pxPerPt: Double, tile: Rect?): Bitmap? = withContext(dispatcher) {
        if (closed) return@withContext null
        cache.get(key)?.let { return@withContext it }
        val g = pages[index]
        val fullW = max(1.0, g.displayWidthPt * pxPerPt)
        val fullH = max(1.0, g.displayHeightPt * pxPerPt)
        val bw = tile?.width() ?: fullW.roundToInt()
        val bh = tile?.height() ?: fullH.roundToInt()
        val bitmap = try {
            Bitmap.createBitmap(max(1, bw), max(1, bh), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            return@withContext null
        }
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(index).use { page ->
            // O PdfRenderer usa o tamanho da página já girada; esticamos para o nosso espaço de exibição.
            val m = Matrix()
            m.setScale((fullW / page.width).toFloat(), (fullH / page.height).toFloat())
            if (tile != null) m.postTranslate(-tile.left.toFloat(), -tile.top.toFloat())
            page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        cache.put(key, bitmap)
        bitmap
    }

    /** Descarta tiles de níveis que não estão em uso, mantendo as imagens base. */
    fun trimTiles(keep: Set<String>) {
        cache.snapshot().keys.filter { !it.endsWith("-base") && it !in keep }.forEach { cache.remove(it) }
    }

    override fun close() {
        closed = true
        // fecha na própria thread de renderização para não competir com uma página aberta
        dispatcher.executor.execute {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
            cache.evictAll()
        }
        dispatcher.close()
    }

    companion object {
        const val TILE_PX = 512
        const val BASE_MAX_PX = 1600.0
        const val BASE_MAX_PX_PER_PT = 3.0
        /** Teto de nitidez: 8 px por ponto ≈ 576 dpi. */
        const val MAX_PX_PER_PT = 8.0

        private fun cacheBytes(): Int {
            val max = Runtime.getRuntime().maxMemory()
            return min(max / 4, 192L * 1024 * 1024).toInt()
        }
    }
}
