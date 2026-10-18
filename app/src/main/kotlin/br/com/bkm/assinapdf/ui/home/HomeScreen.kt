package br.com.bkm.assinapdf.ui.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.bkm.assinapdf.data.RecentDocument
import br.com.bkm.assinapdf.sharing.ShareLauncher
import br.com.bkm.assinapdf.ui.AppUiState
import br.com.bkm.assinapdf.ui.AppViewModel
import br.com.bkm.assinapdf.ui.components.Aviso
import br.com.bkm.assinapdf.ui.components.BotaoLink
import br.com.bkm.assinapdf.ui.components.BotaoPrincipal
import br.com.bkm.assinapdf.ui.components.Rotulo
import br.com.bkm.assinapdf.ui.components.Selo
import br.com.bkm.assinapdf.ui.theme.BkmIcons
import br.com.bkm.assinapdf.ui.theme.BkmTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: AppViewModel, state: AppUiState) {
    val colors = BkmTheme.colors
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openUri(uri)
    }
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Seleção de PDFs assinados para compartilhar juntos (toque longo, como na galeria)
    var selection by rememberSaveable { mutableStateOf(listOf<String>()) }
    val signedIds = state.recents.filter { it.signed }.map { it.id }
    val selected = selection.filter { it in signedIds }
    val selecting = selected.isNotEmpty()
    fun toggle(r: RecentDocument) {
        if (!r.signed) {
            vm.message("Só documentos assinados podem ser selecionados para compartilhar.")
            return
        }
        selection = if (r.id in selected) selected - r.id else selected + r.id
    }
    fun shareSelected() {
        val ids = selected
        scope.launch {
            val files = vm.signedFiles(ids)
            if (files.isEmpty()) {
                vm.message("Os arquivos selecionados não estão mais neste aparelho.")
                return@launch
            }
            if (files.size < ids.size) vm.message("${ids.size - files.size} arquivo(s) não encontrado(s) ficaram de fora.")
            ShareLauncher.shareAll(context, files)
            selection = emptyList()
        }
    }
    BackHandler(enabled = selecting) { selection = emptyList() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = if (selecting) 96.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                if (selecting) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selection = emptyList() }) { Icon(BkmIcons.Fechar, contentDescription = "Cancelar seleção") }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (selected.size == 1) "1 selecionado" else "${selected.size} selecionados",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.texto,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected.size < signedIds.size) {
                            BotaoLink("Selecionar todos", onClick = { selection = signedIds })
                        } else {
                            BotaoLink("Desmarcar todos", onClick = { selection = emptyList() })
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val symbol = remember { BkmIcons.simboloCasa(BkmIcons.gradienteMarca) }
                        Image(rememberVectorPainter(symbol), contentDescription = null, modifier = Modifier.size(width = 22.dp, height = 24.dp))
                        Spacer(Modifier.width(12.dp))
                        Rotulo("Assinatura digital ICP-Brasil", Modifier.weight(1f))
                        IconButton(onClick = { vm.openSettings() }) { Icon(BkmIcons.Ajustes, contentDescription = "Configurações") }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 24.dp)) {
                    Text("AssinaPDF", style = MaterialTheme.typography.displaySmall, color = colors.texto)
                    Text(
                        "Abra um PDF aqui ou compartilhe pelo WhatsApp, posicione os carimbos e assine com o seu certificado SafeID.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textoSuave,
                    )
                }
            }
            item {
                BotaoPrincipal(
                    "Abrir PDF",
                    onClick = { picker.launch(arrayOf("application/pdf")) },
                    icon = BkmIcons.Pasta,
                    seta = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (!state.signingConfigured) {
                item {
                    Aviso("Assinatura SafeID ainda não configurada neste APK. O editor e a exportação de rascunho funcionam normalmente.", alerta = true)
                }
            }
            state.resumable?.let { session ->
                item {
                    Surface(
                        onClick = { vm.resume(session.documentId) },
                        shape = RoundedCornerShape(20.dp),
                        color = colors.superficieElevada,
                        contentColor = colors.texto,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Rotulo("Continuar edição")
                            Text(session.originalName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${session.sheet.stamps.size} carimbo(s) · ${session.pageCount} página(s)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textoSuave,
                            )
                        }
                    }
                }
            }
            if (state.recents.isNotEmpty()) {
                item { Rotulo("Recentes", Modifier.padding(top = 16.dp)) }
                items(state.recents, key = { it.id }) { r ->
                    val isSelected = r.id in selected
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) colors.superficieElevada else Color.Transparent, RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClickLabel = if (selecting) "Marcar ou desmarcar" else "Abrir",
                                    onLongClickLabel = "Selecionar para compartilhar",
                                    onLongClick = { toggle(r) },
                                    onClick = { if (selecting) toggle(r) else vm.resume(r.id) },
                                )
                                .semantics { this.selected = isSelected }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selecting) {
                                MarcaSelecao(isSelected, enabled = r.signed)
                            } else {
                                Icon(BkmIcons.Documento, contentDescription = null, tint = colors.destaque, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.signedFileName ?: r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(dateFmt.format(Date(r.openedAtMs)), style = MaterialTheme.typography.bodySmall, color = colors.textoSuave)
                            }
                            if (r.signed) Selo("Assinado", destaque = true) else Selo("Em edição")
                        }
                        HorizontalDivider(color = colors.linha)
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
        if (selecting) {
            Surface(
                color = colors.superficie,
                shadowElevation = 8.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                BotaoPrincipal(
                    if (selected.size == 1) "Compartilhar 1 PDF" else "Compartilhar ${selected.size} PDFs",
                    onClick = { shareSelected() },
                    icon = BkmIcons.Compartilhar,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** Círculo de seleção, como na galeria: preenchido com visto quando marcado. */
@Composable
private fun MarcaSelecao(checked: Boolean, enabled: Boolean) {
    val colors = BkmTheme.colors
    val shape = CircleShape
    val base = Modifier.size(24.dp)
    if (checked) {
        Box(base.background(colors.acao, shape), contentAlignment = Alignment.Center) {
            Icon(BkmIcons.Confirmar, contentDescription = null, tint = colors.sobreAcao, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(base.border(1.5.dp, if (enabled) colors.bordaControle else colors.linha, shape))
    }
}
