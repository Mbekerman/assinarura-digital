package br.com.bkm.assinapdf.stamps

import kotlinx.serialization.Serializable

/** Valores herdados pelos próximos carimbos inseridos. */
@Serializable
data class StampDefaults(
    val widthFraction: Double = DEFAULT_WIDTH_FRACTION,
    val rotationDeg: Double = 0.0,
) {
    companion object {
        /** 20 % da largura da página: 42 mm em A4, 84 mm em A2, ~168 mm em A0. */
        const val DEFAULT_WIDTH_FRACTION = 0.20
        val FACTORY = StampDefaults()
    }
}

/** Estado completo da edição de um documento (o que é guardado no histórico e persistido). */
@Serializable
data class StampSheet(
    val stamps: List<Stamp> = emptyList(),
    val selectedId: String? = null,
    val lastUsed: StampDefaults = StampDefaults.FACTORY,
) {
    val selected: Stamp? get() = selectedId?.let { id -> stamps.firstOrNull { it.id == id } }
    fun onPage(index: Int): List<Stamp> = stamps.filter { it.pageIndex == index }
}

/** Histórico linear de desfazer/refazer com limite de tamanho. */
class History<T>(initial: T, private val limit: Int = 200) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Registra um novo estado como passo de histórico (limpa a pilha de refazer). */
    fun commit(next: T) {
        if (next == current) return
        undoStack.addLast(current)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        current = next
    }

    /** Altera o estado atual sem criar passo de histórico (usado durante um arraste). */
    fun replaceCurrent(next: T) {
        current = next
    }

    fun undo(): T? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        current = prev
        return prev
    }

    fun redo(): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        current = next
        return next
    }

    fun reset(state: T) {
        undoStack.clear()
        redoStack.clear()
        current = state
    }
}

/**
 * Lógica de edição dos carimbos, independente de Android e de Compose.
 *
 * Operações discretas (inserir, excluir, duplicar, girar 90°, restaurar) geram um passo de
 * histórico cada. Operações contínuas (arrastar, redimensionar, girar pela alça) devem ser
 * envolvidas por [beginGesture] / [endGesture] para que o gesto inteiro seja um único passo.
 */
class StampEditor(
    pages: List<PageGeometry>,
    initial: StampSheet = StampSheet(),
    private val onChange: (StampSheet) -> Unit = {},
) {
    var pages: List<PageGeometry> = pages
        private set
    private val history = History(initial)
    private var gestureStart: StampSheet? = null

    val sheet: StampSheet get() = history.current
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    fun page(index: Int): PageGeometry = pages[index]

    fun replacePages(newPages: List<PageGeometry>) {
        pages = newPages
    }

    private fun commit(next: StampSheet) {
        if (gestureStart != null) {
            history.replaceCurrent(next)
        } else {
            history.commit(next)
        }
        onChange(history.current)
    }

    private fun update(transform: (StampSheet) -> StampSheet) = commit(transform(sheet))

    // ---- Gestos contínuos ----

    fun beginGesture() {
        if (gestureStart == null) gestureStart = sheet
    }

    /** Fecha o gesto: o estado inicial vira o passo anterior do histórico e o final, o atual. */
    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        val end = history.current
        if (start != end) {
            history.replaceCurrent(start)
            history.commit(end)
        }
        // o tamanho/rotação do carimbo manipulado passam a ser os padrões herdados
        val selected = end.selected
        if (selected != null) {
            val defaults = StampDefaults(selected.widthFraction, selected.rotationDeg)
            if (defaults != end.lastUsed) {
                history.replaceCurrent(end.copy(lastUsed = defaults))
            }
        }
        onChange(history.current)
    }

    // ---- Operações ----

    /** Insere um carimbo com centro em (nx, ny) normalizados da página, herdando tamanho e rotação do último configurado. */
    fun addAt(pageIndex: Int, nx: Double, ny: Double): Stamp {
        val page = page(pageIndex)
        val defaults = sheet.lastUsed
        var stamp = Stamp(
            pageIndex = pageIndex,
            centerX = nx,
            centerY = ny,
            widthFraction = StampRules.clampWidthFraction(defaults.widthFraction),
            rotationDeg = Stamp.normalizeDegrees(defaults.rotationDeg),
        )
        stamp = StampRules.keepCenterInside(stamp)
        // em páginas muito estreitas em relação ao carimbo girado, reduz para caber
        val maxFraction = StampRules.maxWidthFractionFor(page, stamp.rotationDeg, stamp.aspectRatio)
        if (stamp.widthFraction > maxFraction) stamp = stamp.copy(widthFraction = maxFraction)
        update { it.copy(stamps = it.stamps + stamp, selectedId = stamp.id) }
        return stamp
    }

    fun select(id: String?) {
        if (id != null && sheet.stamps.none { it.id == id }) return
        if (sheet.selectedId == id) return
        // seleção não entra no histórico de edição
        history.replaceCurrent(sheet.copy(selectedId = id))
        onChange(sheet)
    }

    fun replaceStamp(updated: Stamp) = update { s ->
        s.copy(stamps = s.stamps.map { if (it.id == updated.id) updated else it })
    }

    fun move(id: String, dxPt: Double, dyPt: Double) {
        val stamp = find(id) ?: return
        replaceStamp(StampInteraction.moved(stamp, page(stamp.pageIndex), dxPt, dyPt))
    }

    fun resizeByCorner(id: String, pointerXd: Double, pointerYd: Double) {
        val stamp = find(id) ?: return
        replaceStamp(StampInteraction.resizedByCorner(stamp, page(stamp.pageIndex), pointerXd, pointerYd))
    }

    fun rotateByHandle(id: String, pointerXd: Double, pointerYd: Double, snap: Boolean = true) {
        val stamp = find(id) ?: return
        replaceStamp(StampInteraction.rotatedByHandle(stamp, page(stamp.pageIndex), pointerXd, pointerYd, snap))
    }

    fun rotateBy(id: String, deltaDeg: Double) {
        val stamp = find(id) ?: return
        replaceStamp(StampInteraction.rotatedBy(stamp, deltaDeg))
    }

    fun scaleBy(id: String, factor: Double) {
        val stamp = find(id) ?: return
        replaceStamp(StampInteraction.scaledBy(stamp, factor))
    }

    /** Define a rotação exata; também atualiza o padrão herdado. */
    fun setRotation(id: String, deg: Double) {
        val stamp = find(id) ?: return
        val updated = stamp.withRotation(deg)
        update { s -> s.copy(stamps = s.stamps.map { if (it.id == id) updated else it }, lastUsed = StampDefaults(updated.widthFraction, updated.rotationDeg)) }
    }

    /** Define a largura como fração da página; também atualiza o padrão herdado. */
    fun setWidthFraction(id: String, fraction: Double) {
        val stamp = find(id) ?: return
        val updated = stamp.copy(widthFraction = StampRules.clampWidthFraction(fraction))
        update { s -> s.copy(stamps = s.stamps.map { if (it.id == id) updated else it }, lastUsed = StampDefaults(updated.widthFraction, updated.rotationDeg)) }
    }

    /** Define a largura exata em milímetros na página do carimbo. */
    fun setWidthMm(id: String, mm: Double) {
        val stamp = find(id) ?: return
        val page = page(stamp.pageIndex)
        setWidthFraction(id, page.mmToPt(mm) / page.displayWidthPt)
    }

    fun rotate90(id: String, clockwise: Boolean) {
        val stamp = find(id) ?: return
        setRotation(id, stamp.rotationDeg + if (clockwise) 90.0 else -90.0)
    }

    fun delete(id: String) = update { s ->
        s.copy(stamps = s.stamps.filterNot { it.id == id }, selectedId = if (s.selectedId == id) null else s.selectedId)
    }

    fun deleteSelected() {
        sheet.selectedId?.let { delete(it) }
    }

    /** Duplica o carimbo com um pequeno deslocamento, mantendo-o dentro da página. */
    fun duplicate(id: String): Stamp? {
        val stamp = find(id) ?: return null
        val page = page(stamp.pageIndex)
        val offsetPt = stamp.heightPt(page) * 0.6
        var copy = stamp.copy(id = Stamp.newId(), centerX = stamp.centerX + offsetPt / page.displayWidthPt, centerY = stamp.centerY + offsetPt / page.displayHeightPt)
        copy = StampRules.keepCenterInside(copy)
        update { it.copy(stamps = it.stamps + copy, selectedId = copy.id) }
        return copy
    }

    /** Restaura o carimbo (e o padrão herdado) ao tamanho padrão configurado e rotação 0°. */
    fun resetToFactory(id: String, defaults: StampDefaults = StampDefaults.FACTORY) {
        val stamp = find(id) ?: return
        val updated = stamp.copy(widthFraction = StampRules.clampWidthFraction(defaults.widthFraction), rotationDeg = Stamp.normalizeDegrees(defaults.rotationDeg))
        update { s -> s.copy(stamps = s.stamps.map { if (it.id == id) updated else it }, lastUsed = defaults) }
    }

    /** Restaura apenas o padrão herdado pelos próximos carimbos. */
    fun resetDefaults(defaults: StampDefaults = StampDefaults.FACTORY) = update { it.copy(lastUsed = defaults) }

    /** Transformação livre durante um gesto (pinça e rotação de dois dedos sobre o carimbo). */
    fun transformDuringGesture(id: String, scaleFactor: Double, rotationDeg: Double) {
        val stamp = find(id) ?: return
        val scaled = StampInteraction.scaledBy(stamp, scaleFactor)
        replaceStamp(scaled.withRotation(StampRules.snapRotation(rotationDeg)))
    }

    /** Aplica a rotação de [sourceId] ao carimbo [targetId]. */
    fun applyRotationFrom(sourceId: String, targetId: String) {
        val source = find(sourceId) ?: return
        setRotation(targetId, source.rotationDeg)
    }

    /** Aplica a rotação do carimbo [sourceId] a todos os outros. */
    fun applyRotationToAll(sourceId: String) {
        val source = find(sourceId) ?: return
        update { s -> s.copy(stamps = s.stamps.map { it.withRotation(source.rotationDeg) }) }
    }

    /** Ajusta o carimbo para caber inteiramente dentro da página. */
    fun fitInsidePage(id: String) {
        val stamp = find(id) ?: return
        replaceStamp(StampRules.fitInsidePage(stamp, page(stamp.pageIndex)))
    }

    fun undo(): Boolean {
        gestureStart = null
        val s = history.undo() ?: return false
        onChange(s)
        return true
    }

    fun redo(): Boolean {
        gestureStart = null
        val s = history.redo() ?: return false
        onChange(s)
        return true
    }

    fun restore(sheet: StampSheet) {
        gestureStart = null
        history.reset(sheet)
        onChange(sheet)
    }

    private fun find(id: String): Stamp? = sheet.stamps.firstOrNull { it.id == id }
}
