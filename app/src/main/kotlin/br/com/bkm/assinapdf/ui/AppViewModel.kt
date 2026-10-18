package br.com.bkm.assinapdf.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.bkm.assinapdf.BuildConfig
import br.com.bkm.assinapdf.app.AppContainer
import br.com.bkm.assinapdf.app.AssinaPdfApp
import br.com.bkm.assinapdf.data.AppSettings
import br.com.bkm.assinapdf.data.RecentDocument
import br.com.bkm.assinapdf.pdf.engine.PdfInfo
import br.com.bkm.assinapdf.pdf.engine.PdfInspector
import br.com.bkm.assinapdf.pdf.engine.PdfOpenException
import br.com.bkm.assinapdf.pdf.engine.PdfSignatureVerifier
import br.com.bkm.assinapdf.pdf.engine.PdfVerification
import br.com.bkm.assinapdf.pdf.engine.StampPdfWriter
import br.com.bkm.assinapdf.pdf.engine.StampStyle
import br.com.bkm.assinapdf.pdf.render.PdfDocumentRenderer
import br.com.bkm.assinapdf.sharing.FileNames
import br.com.bkm.assinapdf.sharing.ImportException
import br.com.bkm.assinapdf.sharing.Incoming
import br.com.bkm.assinapdf.sharing.IncomingIntents
import br.com.bkm.assinapdf.signing.AccessToken
import br.com.bkm.assinapdf.signing.CloudCertificate
import br.com.bkm.assinapdf.signing.SigningException
import br.com.bkm.assinapdf.signing.SigningService
import br.com.bkm.assinapdf.stamps.DocumentLayout
import br.com.bkm.assinapdf.stamps.EditingSession
import br.com.bkm.assinapdf.stamps.SignatureStatus
import br.com.bkm.assinapdf.stamps.StampContent
import br.com.bkm.assinapdf.stamps.StampDefaults
import br.com.bkm.assinapdf.stamps.StampEditor
import br.com.bkm.assinapdf.stamps.StampSheet
import br.com.bkm.assinapdf.stamps.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, EDITOR, OUTPUT, SETTINGS }

data class OpenDocument(val session: EditingSession, val info: PdfInfo)

sealed interface SignUi {
    data object Idle : SignUi
    data object Authorizing : SignUi
    data class Working(val step: String) : SignUi
    data class ChooseCertificate(val options: List<CloudCertificate>) : SignUi
    data class Failed(val message: String, val canRetry: Boolean) : SignUi
}

/** Arquivo produzido: PDF assinado (verificado) ou rascunho sem assinatura. */
data class OutputUi(
    val file: File,
    val signed: Boolean,
    val holder: String?,
    val time: String?,
    val issuer: String?,
    val verification: PdfVerification?,
)

data class AppUiState(
    val screen: Screen = Screen.HOME,
    val loading: String? = null,
    val document: OpenDocument? = null,
    val sheet: StampSheet = StampSheet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val currentPage: Int = 0,
    val settings: AppSettings = AppSettings(),
    val recents: List<RecentDocument> = emptyList(),
    val resumable: EditingSession? = null,
    val sign: SignUi = SignUi.Idle,
    val output: OutputUi? = null,
    val verificationDetails: PdfVerification? = null,
    val signingConfigured: Boolean = false,
    val signingProblem: String? = null,
    val sessionActive: Boolean = false,
)

sealed interface UiEffect {
    data class OpenUrl(val url: String) : UiEffect
    data class Message(val text: String) : UiEffect
}

class AppViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        AppUiState(signingConfigured = c.signing.isConfigured, signingProblem = c.signing.configProblem),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val effects = Channel<UiEffect>(Channel.BUFFERED)
    val effectFlow = effects.receiveAsFlow()

    // Estado de renderização e edição (vive enquanto o documento estiver aberto)
    var renderer: PdfDocumentRenderer? by mutableStateOf(null)
        private set
    var layout: DocumentLayout? by mutableStateOf(null)
        private set
    /** Transformação atual do visualizador. O zoom muda só isto, nunca os carimbos. */
    var viewport: Viewport? by mutableStateOf(null)
    private var editor: StampEditor? = null
    private var viewWidth = 0.0
    private var viewHeight = 0.0
    private var viewPadding = 0.0
    private var lastUsedJob: Job? = null

    private var saveJob: Job? = null
    private var authLaunchedAtMs = 0L
    private var redirectInFlight = false
    private var sessionRetry = false
    private var cachedToken: AccessToken? = null

    init {
        viewModelScope.launch { c.settings.settings.collect { s -> _state.update { it.copy(settings = s) } } }
        viewModelScope.launch { c.settings.recents.collect { r -> _state.update { it.copy(recents = r) } } }
        viewModelScope.launch {
            _state.update { it.copy(sessionActive = c.signing.cachedToken() != null) }
            // Autorização interrompida pelo encerramento do app: reabre o documento para concluir.
            val pending = c.signing.pendingAuthorization()
            if (pending != null && _state.value.document == null) {
                c.sessions.load(pending.documentId)?.let { openSession(it) }
                _state.update { it.copy(sign = SignUi.Authorizing) }
            }
            refreshResumable()
        }
    }

    private suspend fun refreshResumable() {
        val latest = c.sessions.latestUnfinished()
        _state.update { it.copy(resumable = latest?.takeIf { s -> s.documentId != it.document?.session?.documentId }) }
    }

    // ---------------------------------------------------------------- Intents

    fun handleIntent(intent: Intent?) {
        when (val incoming = IncomingIntents.parse(intent, BuildConfig.SAFEID_REDIRECT_URI)) {
            is Incoming.Pdf -> openUri(incoming.uri)
            is Incoming.AuthRedirect -> onAuthRedirect(incoming.uri)
            Incoming.None -> Unit
        }
    }

    fun openUri(uri: Uri) {
        _state.update { it.copy(screen = Screen.EDITOR, loading = "Abrindo documento") }
        viewModelScope.launch {
            try {
                val imported = c.importer.import(uri)
                val info = withContext(Dispatchers.IO) { PdfInspector.inspect(imported.file) }
                val settings = c.settings.current()
                val session = EditingSession(
                    documentId = imported.id,
                    originalName = imported.displayName,
                    sourceUri = imported.sourceUri,
                    localPath = imported.file.absolutePath,
                    pages = info.pages,
                    sheet = StampSheet(lastUsed = StampDefaults(settings.lastWidthFraction, settings.lastRotationDeg)),
                    addMode = settings.addMode,
                    hasExistingSignatures = info.hasSignatures,
                )
                attach(session, info)
                c.sessions.save(session)
                c.settings.upsertRecent(RecentDocument(session.documentId, session.originalName, System.currentTimeMillis()))
            } catch (e: ImportException) {
                fail(e.message ?: "Falha ao abrir o documento.")
            } catch (e: PdfOpenException) {
                fail(e.message ?: "PDF não suportado.")
            } catch (e: Exception) {
                fail("Não foi possível abrir o documento.")
            }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = null, screen = if (it.document == null) Screen.HOME else it.screen) }
        effects.trySend(UiEffect.Message(message))
    }

    /** Reabre uma sessão salva (continuar edição ou recentes). */
    fun resume(documentId: String) {
        viewModelScope.launch {
            val recent = _state.value.recents.firstOrNull { it.id == documentId }
            val session = c.sessions.load(documentId)
            if (session == null) {
                c.settings.removeRecent(documentId)
                effects.trySend(UiEffect.Message("O documento não está mais disponível neste aparelho."))
                return@launch
            }
            if (recent?.signed == true && session.signedPath != null && File(session.signedPath!!).exists()) {
                _state.update {
                    it.copy(
                        screen = Screen.OUTPUT,
                        output = OutputUi(File(session.signedPath!!), signed = true, holder = null, time = null, issuer = null, verification = null),
                    )
                }
                return@launch
            }
            _state.update { it.copy(screen = Screen.EDITOR, loading = "Abrindo documento") }
            openSession(session)
        }
    }

    private suspend fun openSession(session: EditingSession) {
        try {
            val info = withContext(Dispatchers.IO) { PdfInspector.inspect(File(session.localPath)) }
            attach(session, info)
        } catch (e: Exception) {
            fail("Não foi possível reabrir o documento.")
        }
    }

    private suspend fun attach(session: EditingSession, info: PdfInfo) {
        closeRenderer()
        val r = try {
            withContext(Dispatchers.IO) { PdfDocumentRenderer(File(session.localPath), info.pages) }
        } catch (e: Exception) {
            fail("O visualizador do Android não conseguiu exibir este PDF (pode estar protegido ou corrompido).")
            return
        }
        renderer = r
        layout = DocumentLayout(info.pages)
        viewport = null
        editor = StampEditor(info.pages, session.sheet) { sheet -> onSheetChanged(sheet) }
        _state.update {
            it.copy(
                screen = Screen.EDITOR,
                loading = null,
                document = OpenDocument(session, info),
                sheet = session.sheet,
                canUndo = false,
                canRedo = false,
                currentPage = session.currentPage.coerceIn(0, (info.pages.size - 1).coerceAtLeast(0)),
                sign = if (it.sign is SignUi.Authorizing) it.sign else SignUi.Idle,
                output = null,
            )
        }
        refreshResumable()
    }

    private fun closeRenderer() {
        renderer?.close()
        renderer = null
        layout = null
    }

    fun closeDocument() {
        persistNow()
        closeRenderer()
        editor = null
        _state.update { it.copy(screen = Screen.HOME, document = null, sheet = StampSheet(), sign = SignUi.Idle) }
        viewModelScope.launch { refreshResumable() }
    }

    // ---------------------------------------------------------------- Edição

    private fun onSheetChanged(sheet: StampSheet) {
        val e = editor ?: return
        val previous = _state.value.sheet
        _state.update { st ->
            st.copy(
                sheet = sheet,
                canUndo = e.canUndo,
                canRedo = e.canRedo,
                document = st.document?.let { d -> d.copy(session = d.session.copy(sheet = sheet)) },
            )
        }
        if (sheet.lastUsed != previous.lastUsed) {
            lastUsedJob?.cancel()
            lastUsedJob = viewModelScope.launch {
                delay(300)
                c.settings.setLastUsed(sheet.lastUsed)
            }
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            _state.value.document?.session?.let { c.sessions.save(it.copy(currentPage = _state.value.currentPage, addMode = _state.value.settings.addMode)) }
        }
    }

    private fun persistNow() {
        val session = _state.value.document?.session ?: return
        viewModelScope.launch { c.sessions.save(session.copy(currentPage = _state.value.currentPage)) }
    }

    /** Operações do editor expostas à interface. */
    fun edit(block: StampEditor.() -> Unit) {
        editor?.block()
    }

    fun undo() { editor?.undo() }
    fun redo() { editor?.redo() }

    fun setAddMode(on: Boolean) {
        // Atualiza a interface na hora; a gravação no DataStore vem em seguida.
        _state.update { it.copy(settings = it.settings.copy(addMode = on)) }
        viewModelScope.launch { c.settings.setAddMode(on) }
        if (!on) editor?.select(null)
    }

    fun setCurrentPage(index: Int) {
        if (index != _state.value.currentPage) {
            _state.update { it.copy(currentPage = index) }
            scheduleSave()
        }
    }

    /** Tamanho da área do visualizador, informado pelo Canvas. */
    fun onViewSize(width: Double, height: Double, padding: Double) {
        viewWidth = width
        viewHeight = height
        viewPadding = padding
    }

    fun fitWidth() {
        val l = layout ?: return
        if (viewWidth <= 0) return
        viewport = l.fitWidth(_state.value.currentPage.coerceIn(0, l.slots.size - 1), viewWidth, viewHeight, viewPadding)
    }

    fun fitPage() {
        val l = layout ?: return
        if (viewWidth <= 0) return
        viewport = l.fitPage(_state.value.currentPage.coerceIn(0, l.slots.size - 1), viewWidth, viewHeight, viewPadding)
    }

    fun goToPage(index: Int) {
        val l = layout ?: return
        if (viewWidth <= 0 || index !in l.slots.indices) return
        val v = viewport ?: l.fitWidth(index, viewWidth, viewHeight, viewPadding)
        viewport = l.scrollTo(index, v, viewWidth, viewHeight)
        setCurrentPage(index)
    }

    fun resetDefaults() {
        val d = StampDefaults(_state.value.settings.defaultWidthFraction, 0.0)
        val selected = _state.value.sheet.selectedId
        if (selected != null) editor?.resetToFactory(selected, d) else editor?.resetDefaults(d)
    }

    // ---------------------------------------------------------------- Rascunho

    fun exportDraft() {
        val doc = _state.value.document ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = "Gerando rascunho") }
            try {
                val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")).format(Date())
                val out = c.storage.uniqueOutput(doc.session.documentId, FileNames.draftName(doc.session.originalName))
                withContext(Dispatchers.IO) {
                    StampPdfWriter.write(
                        File(doc.session.localPath), out, _state.value.sheet.stamps,
                        StampContent.draft(_state.value.settings.lastHolderName, now), styleFor(_state.value.settings),
                    )
                }
                val session = doc.session.copy(signatureStatus = SignatureStatus.DRAFT_EXPORTED)
                c.sessions.save(session)
                _state.update {
                    it.copy(
                        loading = null,
                        screen = Screen.OUTPUT,
                        document = it.document?.copy(session = session),
                        output = OutputUi(out, signed = false, holder = null, time = now, issuer = null, verification = null),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = null) }
                effects.trySend(UiEffect.Message(e.message ?: "Não foi possível gerar o rascunho."))
            }
        }
    }

    private fun styleFor(s: AppSettings) = StampStyle(whiteBackground = s.whiteBackground)

    // ---------------------------------------------------------------- Assinatura

    fun startSigning() {
        val doc = _state.value.document ?: return
        doc.info.signingBlocker()?.let { return setSign(SignUi.Failed(it, canRetry = false)) }
        if (_state.value.sheet.stamps.isNotEmpty()) {
            doc.info.stampBlocker()?.let { return setSign(SignUi.Failed(it, canRetry = false)) }
        }
        if (!c.signing.isConfigured) {
            return setSign(SignUi.Failed(c.signing.configProblem ?: "Integração SafeID não configurada.", canRetry = false))
        }
        sessionRetry = false
        val token = cachedToken?.takeIf { it.isValid() } ?: c.signing.cachedToken()
        if (token != null) runSigning(token) else launchAuthorization()
    }

    private fun launchAuthorization() {
        val doc = _state.value.document ?: return
        try {
            val url = c.signing.beginAuthorization(doc.session.documentId)
            authLaunchedAtMs = System.currentTimeMillis()
            redirectInFlight = false
            setSign(SignUi.Authorizing)
            effects.trySend(UiEffect.OpenUrl(url))
        } catch (e: SigningException) {
            setSign(SignUi.Failed(e.message ?: "Falha ao iniciar a autorização.", canRetry = false))
        }
    }

    /** O navegador seguro não pôde ser aberto. */
    fun onAuthorizationLaunchFailed() {
        c.signing.cancelAuthorization()
        setSign(SignUi.Failed("Nenhum navegador disponível para autorizar o certificado.", canRetry = false))
    }

    fun onAuthRedirect(uri: Uri) {
        redirectInFlight = true
        setSign(SignUi.Working("Confirmando autorização"))
        viewModelScope.launch {
            try {
                // Se o app foi encerrado durante a autorização, reabre o documento antes de continuar.
                val pending = c.signing.pendingAuthorization()
                if (_state.value.document == null && pending != null) {
                    c.sessions.load(pending.documentId)?.let { openSession(it) }
                }
                val token = c.signing.completeAuthorization(uri)
                cachedToken = token
                _state.update { it.copy(sessionActive = true) }
                runSigning(token)
            } catch (e: Exception) {
                handleSigningError(e)
            } finally {
                redirectInFlight = false
            }
        }
    }

    /** A Activity voltou ao primeiro plano. Se a autorização estava aberta e não houve retorno, o usuário fechou o navegador. */
    fun onActivityResumed() {
        if (_state.value.sign !is SignUi.Authorizing) return
        if (System.currentTimeMillis() - authLaunchedAtMs < 700) return
        viewModelScope.launch {
            delay(900)
            if (_state.value.sign is SignUi.Authorizing && !redirectInFlight) {
                c.signing.cancelAuthorization()
                setSign(SignUi.Failed("A autorização não foi concluída. O documento e os carimbos foram preservados.", canRetry = true))
            }
        }
    }

    private fun runSigning(token: AccessToken) {
        viewModelScope.launch {
            try {
                setSign(SignUi.Working("Consultando certificado"))
                val certs = c.signing.certificates(token)
                val valid = certs.filter { it.info.isValidAt(Date()) }
                when {
                    valid.size == 1 -> sign(token, valid.single())
                    valid.size > 1 -> setSign(SignUi.ChooseCertificate(valid))
                    else -> throw SigningException.CertificateUnavailable("Nenhum certificado válido encontrado no SafeID (todos vencidos ou ainda não válidos).")
                }
            } catch (e: Exception) {
                handleSigningError(e)
            }
        }
    }

    fun chooseCertificate(cert: CloudCertificate) {
        val token = cachedToken ?: c.signing.cachedToken() ?: return launchAuthorization()
        viewModelScope.launch {
            try {
                sign(token, cert)
            } catch (e: Exception) {
                handleSigningError(e)
            }
        }
    }

    private suspend fun sign(token: AccessToken, cert: CloudCertificate) {
        val doc = _state.value.document ?: return
        c.settings.setLastHolder(cert.info.holderName)
        val outcome = c.signing.signDocument(
            documentId = doc.session.documentId,
            originalName = doc.session.originalName,
            original = File(doc.session.localPath),
            stamps = _state.value.sheet.stamps,
            style = styleFor(_state.value.settings),
            token = token,
            certificate = cert,
        ) { step -> setSign(SignUi.Working(step)) }
        val session = doc.session.copy(signatureStatus = SignatureStatus.SIGNED, signedPath = outcome.file.absolutePath)
        c.sessions.save(session)
        c.settings.upsertRecent(RecentDocument(session.documentId, session.originalName, System.currentTimeMillis(), signed = true, signedFileName = outcome.file.name))
        _state.update {
            it.copy(
                sign = SignUi.Idle,
                screen = Screen.OUTPUT,
                document = it.document?.copy(session = session),
                output = OutputUi(outcome.file, signed = true, holder = outcome.holderName, time = outcome.signingTimeText, issuer = outcome.issuer, verification = outcome.verification),
            )
        }
    }

    private fun handleSigningError(e: Throwable) {
        when (e) {
            is SigningException.SessionExpired -> {
                c.signing.clearToken()
                cachedToken = null
                _state.update { it.copy(sessionActive = false) }
                if (!sessionRetry) {
                    sessionRetry = true
                    launchAuthorization()
                } else {
                    setSign(SignUi.Failed(e.message ?: "Sessão expirada.", canRetry = true))
                }
            }
            is SigningException.Canceled -> setSign(SignUi.Failed(e.message ?: "Autorização cancelada.", canRetry = true))
            is SigningException.NotConfigured, is SigningException.UnsupportedDocument -> setSign(SignUi.Failed(e.message ?: "", canRetry = false))
            is SigningException -> setSign(SignUi.Failed(e.message ?: "Falha na assinatura.", canRetry = true))
            else -> setSign(SignUi.Failed("Falha inesperada na assinatura (${e.javaClass.simpleName}). Nada foi marcado como assinado.", canRetry = true))
        }
    }

    private fun setSign(s: SignUi) = _state.update { it.copy(sign = s) }

    fun dismissSignError() = setSign(SignUi.Idle)

    /** Usuário desistiu enquanto aguardava a autorização. */
    fun cancelSigning() {
        c.signing.cancelAuthorization()
        setSign(SignUi.Idle)
    }

    fun disconnectSafeId() {
        c.signing.clearToken()
        cachedToken = null
        _state.update { it.copy(sessionActive = false) }
        effects.trySend(UiEffect.Message("Sessão do SafeID encerrada neste aparelho."))
    }

    // ---------------------------------------------------------------- Saída

    fun verifyOutput() {
        val out = _state.value.output ?: return
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) { runCatching { PdfSignatureVerifier.verify(out.file) }.getOrNull() }
            if (v == null) effects.trySend(UiEffect.Message("Não foi possível verificar o arquivo."))
            _state.update { it.copy(verificationDetails = v) }
        }
    }

    fun dismissVerification() = _state.update { it.copy(verificationDetails = null) }

    fun backToEditor() {
        if (_state.value.document != null && renderer != null) _state.update { it.copy(screen = Screen.EDITOR) } else goHome()
    }

    fun goHome() {
        if (_state.value.document != null) closeDocument() else _state.update { it.copy(screen = Screen.HOME) }
    }

    fun openSettings() = _state.update { it.copy(screen = Screen.SETTINGS) }

    fun closeSettings() = _state.update { it.copy(screen = if (it.document != null) Screen.EDITOR else Screen.HOME) }

    fun message(text: String) { effects.trySend(UiEffect.Message(text)) }

    // ---------------------------------------------------------------- Configurações

    fun setDefaultWidth(fraction: Double) = viewModelScope.launch { c.settings.setDefaultWidth(fraction) }
    fun setWhiteBackground(on: Boolean) = viewModelScope.launch { c.settings.setWhiteBackground(on) }
    fun setShowMeasurements(on: Boolean) = viewModelScope.launch { c.settings.setShowMeasurements(on) }

    fun cleanTemporaryFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            c.storage.cleanupTemps()
            val keep = _state.value.recents.map { it.id }.toSet() + listOfNotNull(_state.value.document?.session?.documentId)
            c.storage.deleteOlderThan(30L * 24 * 3600 * 1000, keep)
            effects.trySend(UiEffect.Message("Arquivos temporários removidos."))
        }
    }

    override fun onCleared() {
        renderer?.close()
        super.onCleared()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as AssinaPdfApp
                AppViewModel(app.container)
            }
        }
    }
}
