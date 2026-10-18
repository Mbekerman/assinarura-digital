package br.com.bkm.assinapdf.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.com.bkm.assinapdf.pdf.render.PdfDocumentRenderer
import br.com.bkm.assinapdf.stamps.DocumentLayout
import br.com.bkm.assinapdf.stamps.Handle
import br.com.bkm.assinapdf.stamps.PageGeometry
import br.com.bkm.assinapdf.stamps.PageSlot
import br.com.bkm.assinapdf.stamps.Stamp
import br.com.bkm.assinapdf.stamps.StampContent
import br.com.bkm.assinapdf.stamps.StampInteraction
import br.com.bkm.assinapdf.stamps.StampSheet
import br.com.bkm.assinapdf.stamps.StampVisual
import br.com.bkm.assinapdf.stamps.Viewport
import br.com.bkm.assinapdf.ui.AppViewModel
import br.com.bkm.assinapdf.ui.theme.BkmTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Alvo do toque inicial de um gesto. */
private sealed interface Target {
    data class OnHandle(val id: String, val handle: Handle, val slot: PageSlot) : Target
    data class OnStamp(val id: String, val slot: PageSlot) : Target
    data class OnPage(val slot: PageSlot, val xd: Double, val yd: Double) : Target
    data object Outside : Target
}

private enum class Mode { UNDECIDED, PAN, MOVE_STAMP, RESIZE, ROTATE, STAMP_TWO_FINGER, ZOOM }

private data class Tap(val position: Offset, val timeMs: Long)

/**
 * Visualizador contínuo com zoom por pinça, deslocamento, renderização em blocos e edição dos
 * carimbos por gestos.
 *
 * Gestos:
 * - Duplo toque em área vazia: insere carimbo (modo "Adicionar assinaturas") ou alterna zoom.
 * - Toque em carimbo: seleciona. Arrastar carimbo: move. Alças: redimensionar (cantos) e girar.
 * - Pinça com dois dedos: zoom; sobre o carimbo selecionado, escala e gira o carimbo.
 * - Arraste com um dedo fora dos carimbos: desloca o documento.
 * Um duplo toque só conta se nenhum dos toques moveu além da tolerância nem teve dois dedos,
 * o que evita carimbos acidentais durante zoom ou deslocamento.
 */
@Composable
fun PdfEditorCanvas(
    vm: AppViewModel,
    sheet: StampSheet,
    addMode: Boolean,
    holderName: String?,
    whiteBackground: Boolean,
    showMeasurements: Boolean,
    modifier: Modifier = Modifier,
) {
    val renderer = vm.renderer ?: return
    val layout = vm.layout ?: return
    val colors = BkmTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var size by remember { mutableStateOf(IntSize.Zero) }
    var tileVersion by remember { mutableIntStateOf(0) }
    val painter = remember { StampPainter() }
    val previewContent = remember(holderName) { StampContent.preview(holderName) }
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    }

    val currentSheet by rememberUpdatedState(sheet)
    val currentAddMode by rememberUpdatedState(addMode)

    val handleRadiusPx = with(density) { 24.dp.toPx() }
    val handleDrawPx = with(density) { 9.dp.toPx() }
    val rotateOffsetPx = with(density) { 36.dp.toPx() }
    val stampTolerancePx = with(density) { 6.dp.toPx() }
    val doubleTapSlopPx = with(density) { 48.dp.toPx() }
    val paddingPx = with(density) { 12.dp.toPx() }
    val labelTextPx = with(density) { 12.dp.toPx() }

    fun minScale(): Double {
        val s = size
        if (s.width == 0) return 0.01
        return layout.slots.minOf { min(s.width / it.width, s.height / it.height) } * 0.5
    }
    fun maxScale(): Double = PdfDocumentRenderer.MAX_PX_PER_PT * 1.25

    fun setViewport(v: Viewport) {
        vm.viewport = layout.clamp(v, size.width.toDouble(), size.height.toDouble())
    }

    // Viewport inicial: ajusta à largura da página atual
    LaunchedEffect(size, layout) {
        vm.onViewSize(size.width.toDouble(), size.height.toDouble(), paddingPx.toDouble())
        if (size.width > 0 && vm.viewport == null) {
            val page = vm.state.value.currentPage.coerceIn(0, layout.slots.size - 1)
            vm.viewport = layout.fitWidth(page, size.width.toDouble(), size.height.toDouble(), paddingPx.toDouble())
        }
    }

    // Página atual
    LaunchedEffect(layout) {
        snapshotFlow { vm.viewport to size }.collectLatest { (v, s) ->
            if (v != null && s.width > 0) vm.setCurrentPage(layout.currentPage(v, s.width.toDouble(), s.height.toDouble()))
        }
    }

    // Renderização progressiva: base das páginas visíveis e depois blocos nítidos da área visível
    LaunchedEffect(renderer, layout) {
        snapshotFlow { vm.viewport to size }.collectLatest { (v, s) ->
            if (v == null || s.width == 0) return@collectLatest
            delay(50)
            val visible = layout.visible(v, s.width.toDouble(), s.height.toDouble())
            for (slot in visible) {
                if (renderer.cached(renderer.baseKey(slot.index)) == null) {
                    renderer.renderBase(slot.index)
                    tileVersion++
                }
            }
            delay(120) // espera o gesto assentar antes dos blocos de alta resolução
            val keep = HashSet<String>()
            for (slot in visible) {
                val level = renderer.tileLevel(slot.index, v.scale) ?: continue
                forEachVisibleTile(v, slot, level, s) { tx, ty ->
                    keep += renderer.tileKey(slot.index, level, tx, ty)
                }
            }
            for (slot in visible) {
                val level = renderer.tileLevel(slot.index, v.scale) ?: continue
                val tiles = ArrayList<Pair<Int, Int>>()
                forEachVisibleTile(v, slot, level, s) { tx, ty -> tiles += tx to ty }
                for ((tx, ty) in tiles) {
                    if (renderer.cached(renderer.tileKey(slot.index, level, tx, ty)) == null) {
                        renderer.renderTile(slot.index, level, tx, ty)
                        tileVersion++
                    }
                }
            }
            renderer.trimTiles(keep)
            // pré-carrega as páginas vizinhas em resolução base
            val current = layout.currentPage(v, s.width.toDouble(), s.height.toDouble())
            for (i in listOf(current + 1, current - 1)) {
                if (i in layout.slots.indices && renderer.cached(renderer.baseKey(i)) == null) renderer.renderBase(i)
            }
        }
    }

    var lastTap by remember { mutableStateOf<Tap?>(null) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    fun animateTo(target: Viewport) {
        val start = vm.viewport ?: return
        flingJob?.cancel()
        flingJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(220)) { f, _ ->
                val scale = exp(ln(start.scale) + (ln(target.scale) - ln(start.scale)) * f)
                setViewport(
                    Viewport(
                        scale,
                        start.offsetX + (target.offsetX - start.offsetX) * f,
                        start.offsetY + (target.offsetY - start.offsetY) * f,
                    ),
                )
            }
        }
    }

    fun target(pos: Offset): Target {
        val v = vm.viewport ?: return Target.Outside
        val dx = v.toDisplayX(pos.x.toDouble())
        val dy = v.toDisplayY(pos.y.toDouble())
        val sel = currentSheet.selected
        if (sel != null) {
            val slot = layout.slots[sel.pageIndex]
            val page = layout.pages[sel.pageIndex]
            val (xd, yd) = layout.toPage(slot, dx, dy)
            StampInteraction.hitHandle(sel, page, xd, yd, handleRadiusPx / v.scale, rotateOffsetPx / v.scale)
                ?.let { return Target.OnHandle(sel.id, it, slot) }
        }
        val slot = layout.pageAt(dx, dy) ?: return Target.Outside
        val page = layout.pages[slot.index]
        val (xd, yd) = layout.toPage(slot, dx, dy)
        val hit = StampInteraction.hitStamp(currentSheet.stamps, page, xd, yd, stampTolerancePx / v.scale)
        return if (hit != null) Target.OnStamp(hit.id, slot) else Target.OnPage(slot, xd, yd)
    }

    fun toggleZoom(pos: Offset, pageIndex: Int) {
        val v = vm.viewport ?: return
        val fit = layout.fitWidth(pageIndex, size.width.toDouble(), size.height.toDouble(), paddingPx.toDouble())
        val target = if (v.scale < fit.scale * 1.6) {
            v.zoomedAround(2.5, pos.x.toDouble(), pos.y.toDouble(), minScale(), maxScale())
        } else {
            fit
        }
        animateTo(layout.clamp(target, size.width.toDouble(), size.height.toDouble()))
    }

    fun onTap(pos: Offset, timeMs: Long, viewConfigTimeout: Long) {
        val previous = lastTap
        val isDouble = previous != null && timeMs - previous.timeMs <= viewConfigTimeout &&
            (pos - previous.position).getDistance() <= doubleTapSlopPx
        val t = target(pos)
        if (!isDouble) {
            lastTap = Tap(pos, timeMs)
            when (t) {
                is Target.OnStamp -> vm.edit { select(t.id) }
                is Target.OnHandle -> Unit
                else -> vm.edit { select(null) }
            }
            return
        }
        lastTap = null
        when (t) {
            is Target.OnStamp, is Target.OnHandle -> Unit // toque sobre carimbo: só seleção
            is Target.OnPage -> {
                if (currentAddMode) {
                    val page = layout.pages[t.slot.index]
                    vm.edit { addAt(t.slot.index, t.xd / page.displayWidthPt, t.yd / page.displayHeightPt) }
                } else {
                    toggleZoom(pos, t.slot.index)
                }
            }
            Target.Outside -> if (!currentAddMode) toggleZoom(pos, vm.state.value.currentPage)
        }
    }


    Canvas(
        modifier
            .onSizeChanged { size = it }
            .semantics { contentDescription = "Documento PDF" }
            .pointerInput(layout) {
                val slop = viewConfiguration.touchSlop
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val longPress = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    flingJob?.cancel()
                    val startPos = down.position
                    val startTime = down.uptimeMillis
                    val target = target(startPos)
                    var mode = Mode.UNDECIDED
                    var moved = false
                    var multiTouch = false
                    var gestureOpen = false
                    var rawRotation = 0.0
                    var twoFingerId: String? = null
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    fun openGesture() {
                        if (!gestureOpen) {
                            vm.edit { beginGesture() }
                            gestureOpen = true
                        }
                    }

                    if (target is Target.OnHandle) {
                        mode = if (target.handle == Handle.ROTATE) Mode.ROTATE else Mode.RESIZE
                        openGesture()
                        down.consume()
                    }

                    var upTime = startTime
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            upTime = event.changes.firstOrNull()?.uptimeMillis ?: upTime
                            break
                        }
                        val v = vm.viewport ?: continue
                        if (pressed.size >= 2) {
                            multiTouch = true
                            val zoom = event.calculateZoom()
                            val rotation = event.calculateRotation()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val selected = currentSheet.selected
                            val onSelectedStamp = (target is Target.OnStamp && target.id == selected?.id) || mode == Mode.STAMP_TWO_FINGER
                            if (onSelectedStamp && selected != null && mode != Mode.ZOOM) {
                                if (mode != Mode.STAMP_TWO_FINGER) {
                                    mode = Mode.STAMP_TWO_FINGER
                                    twoFingerId = selected.id
                                    rawRotation = selected.rotationDeg
                                    openGesture()
                                }
                                rawRotation += rotation
                                val id = twoFingerId!!
                                vm.edit { transformDuringGesture(id, zoom.toDouble(), rawRotation) }
                            } else if (mode != Mode.MOVE_STAMP && mode != Mode.RESIZE && mode != Mode.ROTATE) {
                                mode = Mode.ZOOM
                                if (centroid != Offset.Unspecified) {
                                    setViewport(
                                        v.zoomedAround(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble(), minScale(), maxScale())
                                            .panned(pan.x.toDouble(), pan.y.toDouble()),
                                    )
                                }
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val change: PointerInputChange = pressed.first()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!moved && (change.position - startPos).getDistance() > slop) moved = true
                        if (!moved) continue
                        val delta = change.positionChange()
                        when (mode) {
                            Mode.RESIZE, Mode.ROTATE -> {
                                val t = target as Target.OnHandle
                                val (xd, yd) = layout.toPage(t.slot, v.toDisplayX(change.position.x.toDouble()), v.toDisplayY(change.position.y.toDouble()))
                                if (mode == Mode.RESIZE) vm.edit { resizeByCorner(t.id, xd, yd) } else vm.edit { rotateByHandle(t.id, xd, yd) }
                            }
                            Mode.MOVE_STAMP -> {
                                val t = target as Target.OnStamp
                                vm.edit { move(t.id, delta.x / v.scale, delta.y / v.scale) }
                            }
                            Mode.PAN -> setViewport(v.panned(delta.x.toDouble(), delta.y.toDouble()))
                            Mode.UNDECIDED -> {
                                if (target is Target.OnStamp && !multiTouch) {
                                    mode = Mode.MOVE_STAMP
                                    vm.edit { select(target.id) }
                                    openGesture()
                                    val total = change.position - startPos
                                    vm.edit { move(target.id, total.x / v.scale, total.y / v.scale) }
                                } else if (!multiTouch) {
                                    mode = Mode.PAN
                                    setViewport(v.panned(delta.x.toDouble(), delta.y.toDouble()))
                                }
                            }
                            Mode.STAMP_TWO_FINGER, Mode.ZOOM -> Unit // um dedo saiu: aguarda o fim
                        }
                        change.consume()
                    }

                    if (gestureOpen) vm.edit { endGesture() }
                    if (multiTouch) {
                        lastTap = null
                    } else if (!moved && upTime - startTime < longPress && mode != Mode.RESIZE && mode != Mode.ROTATE) {
                        onTap(startPos, upTime, doubleTapTimeout)
                    } else if (mode == Mode.PAN) {
                        val velocity = tracker.calculateVelocity()
                        val speed = hypot(velocity.x, velocity.y)
                        if (speed > 300f) {
                            val dirX = velocity.x / speed
                            val dirY = velocity.y / speed
                            flingJob = scope.launch {
                                var last = 0f
                                animateDecay(0f, speed, FloatExponentialDecaySpec(frictionMultiplier = 1.6f)) { value, _ ->
                                    val d = value - last
                                    last = value
                                    vm.viewport?.let { setViewport(it.panned((dirX * d).toDouble(), (dirY * d).toDouble())) }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        if (tileVersion < 0) return@Canvas // leitura que redesenha quando um bloco termina de renderizar
        val v = vm.viewport ?: return@Canvas
        drawRect(colors.fundoVisualizador)
        val visible = layout.visible(v, size.width.toDouble(), size.height.toDouble())
        for (slot in visible) {
            drawPage(v, slot, renderer, colors.linha)
        }
        for (slot in visible) {
            val page = layout.pages[slot.index]
            for (stamp in currentSheet.stamps) {
                if (stamp.pageIndex != slot.index) continue
                drawStamp(v, slot, page, stamp, painter, previewContent, whiteBackground)
            }
        }
        currentSheet.selected?.let { sel ->
            val slot = layout.slots[sel.pageIndex]
            val page = layout.pages[sel.pageIndex]
            drawSelection(v, slot, page, sel, colors.destaque, handleDrawPx, rotateOffsetPx, showMeasurements, labelPaint, labelTextPx, colors.acao.toArgb(), colors.sobreAcao.toArgb())
        }
    }
}

private inline fun forEachVisibleTile(v: Viewport, slot: PageSlot, level: Double, s: IntSize, block: (Int, Int) -> Unit) {
    val left = max(0.0, v.toDisplayX(0.0) - slot.left)
    val top = max(0.0, v.toDisplayY(0.0) - slot.top)
    val right = min(slot.width, v.toDisplayX(s.width.toDouble()) - slot.left)
    val bottom = min(slot.height, v.toDisplayY(s.height.toDouble()) - slot.top)
    if (right <= left || bottom <= top) return
    val tile = PdfDocumentRenderer.TILE_PX.toDouble()
    val tx0 = (left * level / tile).toInt()
    val ty0 = (top * level / tile).toInt()
    val tx1 = ((right * level - 1) / tile).toInt()
    val ty1 = ((bottom * level - 1) / tile).toInt()
    for (ty in ty0..ty1) for (tx in tx0..tx1) block(tx, ty)
}

private fun DrawScope.drawPage(v: Viewport, slot: PageSlot, renderer: PdfDocumentRenderer, border: Color) {
    val l = v.toScreenX(slot.left).toFloat()
    val t = v.toScreenY(slot.top).toFloat()
    val r = v.toScreenX(slot.right).toFloat()
    val b = v.toScreenY(slot.bottom).toFloat()
    drawRect(Color.White, topLeft = Offset(l, t), size = Size(r - l, b - t))
    renderer.cached(renderer.baseKey(slot.index))?.let { bmp ->
        val il = l.roundToInt()
        val itop = t.roundToInt()
        drawImage(
            bmp.asImageBitmap(),
            dstOffset = IntOffset(il, itop),
            dstSize = IntSize(max(1, r.roundToInt() - il), max(1, b.roundToInt() - itop)),
            filterQuality = FilterQuality.Medium,
        )
    }
    val level = renderer.tileLevel(slot.index, v.scale)
    if (level != null) {
        val tile = PdfDocumentRenderer.TILE_PX
        val s = IntSize(size.width.toInt(), size.height.toInt())
        forEachVisibleTile(v, slot, level, s) { tx, ty ->
            renderer.cached(renderer.tileKey(slot.index, level, tx, ty))?.let { bmp ->
                val x0 = v.toScreenX(slot.left + tx * tile / level).roundToInt()
                val y0 = v.toScreenY(slot.top + ty * tile / level).roundToInt()
                val x1 = v.toScreenX(slot.left + (tx * tile + bmp.width) / level).roundToInt()
                val y1 = v.toScreenY(slot.top + (ty * tile + bmp.height) / level).roundToInt()
                drawImage(bmp.asImageBitmap(), dstOffset = IntOffset(x0, y0), dstSize = IntSize(max(1, x1 - x0), max(1, y1 - y0)), filterQuality = FilterQuality.Medium)
            }
        }
    }
    drawRect(border, topLeft = Offset(l, t), size = Size(r - l, b - t), style = Stroke(1f))
}

private fun DrawScope.drawStamp(
    v: Viewport,
    slot: PageSlot,
    page: PageGeometry,
    stamp: Stamp,
    painter: StampPainter,
    content: StampContent,
    whiteBackground: Boolean,
) {
    val (cx, cy) = stamp.centerPt(page)
    val sx = v.toScreenX(slot.left + cx).toFloat()
    val sy = v.toScreenY(slot.top + cy).toFloat()
    val scale = (v.scale * stamp.widthPt(page) / StampVisual.W).toFloat()
    if (scale * StampVisual.W < 2f) return
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        nc.save()
        nc.translate(sx, sy)
        nc.rotate(stamp.rotationDeg.toFloat())
        nc.scale(scale, scale)
        nc.translate((-StampVisual.W / 2).toFloat(), (-StampVisual.H / 2).toFloat())
        painter.draw(nc, content, whiteBackground)
        nc.restore()
    }
}

private fun DrawScope.drawSelection(
    v: Viewport,
    slot: PageSlot,
    page: PageGeometry,
    stamp: Stamp,
    accent: Color,
    handlePx: Float,
    rotateOffsetPx: Float,
    showMeasurements: Boolean,
    labelPaint: Paint,
    labelTextPx: Float,
    labelBg: Int,
    labelFg: Int,
) {
    fun screen(p: Pair<Double, Double>) = Offset(v.toScreenX(slot.left + p.first).toFloat(), v.toScreenY(slot.top + p.second).toFloat())
    val corners = stamp.cornersPt(page).map(::screen)
    val outline = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (i in 1 until 4) lineTo(corners[i].x, corners[i].y)
        close()
    }
    drawPath(outline, accent, style = Stroke(width = 1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))))
    val handles = StampInteraction.handlePositions(stamp, page, rotateOffsetPx / v.scale)
    val topMid = Offset((corners[0].x + corners[1].x) / 2, (corners[0].y + corners[1].y) / 2)
    val rot = screen(handles.getValue(Handle.ROTATE))
    drawLine(accent, topMid, rot, strokeWidth = 1.5f * density)
    for ((h, p) in handles) {
        val o = screen(p)
        drawCircle(Color.White, radius = handlePx, center = o)
        drawCircle(accent, radius = handlePx, center = o, style = Stroke(width = 2f * density))
        if (h == Handle.ROTATE) {
            drawCircle(accent, radius = handlePx * 0.35f, center = o)
        }
    }
    if (showMeasurements) {
        val text = "${formatMm(stamp.widthMm(page))} mm · ${(stamp.widthFraction * 100).roundToInt()}% · ${stamp.rotationDeg.roundToInt()}°"
        val bottom = corners.maxOf { it.y }
        val cx = corners.map { it.x }.average().toFloat()
        drawIntoCanvas { canvas ->
            labelPaint.textSize = labelTextPx
            val w = labelPaint.measureText(text)
            val padH = 8f * density
            val padV = 4f * density
            val top = bottom + handlePx + 6f * density
            labelPaint.color = labelBg
            canvas.nativeCanvas.drawRoundRect(cx - w / 2 - padH, top, cx + w / 2 + padH, top + labelTextPx + 2 * padV, 999f, 999f, labelPaint)
            labelPaint.color = labelFg
            canvas.nativeCanvas.drawText(text, cx - w / 2, top + padV + labelTextPx * 0.85f, labelPaint)
        }
    }
}

internal fun formatMm(mm: Double): String =
    if (abs(mm - mm.roundToInt()) < 0.05 || mm >= 100) mm.roundToInt().toString() else String.format(java.util.Locale("pt", "BR"), "%.1f", mm)
