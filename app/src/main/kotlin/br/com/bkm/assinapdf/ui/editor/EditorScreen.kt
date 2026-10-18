package br.com.bkm.assinapdf.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.bkm.assinapdf.signing.CloudCertificate
import br.com.bkm.assinapdf.stamps.PageGeometry
import br.com.bkm.assinapdf.stamps.Stamp
import br.com.bkm.assinapdf.stamps.StampRules
import br.com.bkm.assinapdf.ui.AppUiState
import br.com.bkm.assinapdf.ui.AppViewModel
import br.com.bkm.assinapdf.ui.SignUi
import br.com.bkm.assinapdf.ui.components.Aviso
import br.com.bkm.assinapdf.ui.components.BotaoPrincipal
import br.com.bkm.assinapdf.ui.components.Rotulo
import br.com.bkm.assinapdf.ui.theme.BkmIcons
import br.com.bkm.assinapdf.ui.theme.BkmTheme
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel, state: AppUiState) {
    val doc = state.document
    val colors = BkmTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var pagePicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = { vm.goHome() }) { Icon(BkmIcons.Voltar, contentDescription = "Voltar ao início") }
            },
            title = {
                Column(Modifier.clickable(enabled = doc != null) { pagePicker = true }) {
                    Text(
                        doc?.session?.originalName ?: "AssinaPDF",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (doc != null && doc.info.pages.isNotEmpty()) {
                        val page = doc.info.pages[state.currentPage.coerceIn(0, doc.info.pages.size - 1)]
                        Text(
                            "Página ${state.currentPage + 1} de ${doc.info.pages.size} · ${page.describe()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textoSuave,
                            maxLines = 1,
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = { vm.undo() }, enabled = state.canUndo) { Icon(BkmIcons.Desfazer, contentDescription = "Desfazer") }
                IconButton(onClick = { vm.redo() }, enabled = state.canRedo) { Icon(BkmIcons.Refazer, contentDescription = "Refazer") }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(BkmIcons.Mais, contentDescription = "Mais opções") }
                    EditorMenu(vm, state, expanded = menuOpen, onDismiss = { menuOpen = false })
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.superficie),
        )
        HorizontalDivider(color = colors.linha)

        if (doc == null || state.loading != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = colors.destaque)
                    Text(state.loading ?: "Carregando", color = colors.textoSuave)
                }
            }
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            PdfEditorCanvas(
                vm = vm,
                sheet = state.sheet,
                addMode = state.settings.addMode,
                holderName = state.settings.lastHolderName,
                whiteBackground = state.settings.whiteBackground,
                showMeasurements = state.settings.showMeasurements,
                modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.align(Alignment.TopCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (doc.info.hasSignatures) {
                    Aviso(
                        "Este PDF já tem ${doc.info.existingSignatures} assinatura(s). Os carimbos entram como anotações em uma revisão nova, sem alterar o conteúdo já assinado.",
                        alerta = true,
                    )
                }
                if (state.settings.addMode && state.sheet.stamps.isEmpty()) {
                    Aviso("Toque duas vezes na folha para inserir um carimbo.")
                }
            }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FloatingTool(BkmIcons.AjustarLargura, "Ajustar à largura") { vm.fitWidth() }
                FloatingTool(BkmIcons.AjustarPagina, "Ajustar à página") { vm.fitPage() }
            }
        }

        val selected = state.sheet.selected
        AnimatedVisibility(visible = selected != null) {
            if (selected != null) {
                StampControls(vm, selected, doc.info.pages[selected.pageIndex])
            }
        }
        HorizontalDivider(color = colors.linha)
        BottomBar(vm, state)
    }

    if (pagePicker && doc != null) {
        PagePicker(doc.info.pages, onPick = { vm.goToPage(it); pagePicker = false }, onDismiss = { pagePicker = false })
    }
    SigningDialogs(vm, state.sign)
}

@Composable
private fun FloatingTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BkmTheme.colors.superficie,
        contentColor = BkmTheme.colors.texto,
        border = BorderStroke(1.dp, BkmTheme.colors.linha),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun EditorMenu(vm: AppViewModel, state: AppUiState, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Ajustar à largura") }, leadingIcon = { Icon(BkmIcons.AjustarLargura, null) }, onClick = { onDismiss(); vm.fitWidth() })
        DropdownMenuItem(text = { Text("Ajustar à página") }, leadingIcon = { Icon(BkmIcons.AjustarPagina, null) }, onClick = { onDismiss(); vm.fitPage() })
        DropdownMenuItem(
            text = { Text(if (state.sheet.selected != null) "Restaurar tamanho e rotação padrão" else "Restaurar padrão dos próximos carimbos") },
            leadingIcon = { Icon(BkmIcons.Restaurar, null) },
            onClick = { onDismiss(); vm.resetDefaults() },
        )
        DropdownMenuItem(
            text = { Text("Exportar rascunho (sem assinatura)") },
            leadingIcon = { Icon(BkmIcons.Documento, null) },
            enabled = state.sheet.stamps.isNotEmpty(),
            onClick = { onDismiss(); vm.exportDraft() },
        )
        DropdownMenuItem(text = { Text("Configurações") }, leadingIcon = { Icon(BkmIcons.Ajustes, null) }, onClick = { onDismiss(); vm.openSettings() })
        DropdownMenuItem(text = { Text("Fechar documento") }, leadingIcon = { Icon(BkmIcons.Fechar, null) }, onClick = { onDismiss(); vm.closeDocument() })
    }
}

@Composable
private fun BottomBar(vm: AppViewModel, state: AppUiState) {
    val colors = BkmTheme.colors
    val selected = state.sheet.selected
    Row(
        Modifier.fillMaxWidth().background(colors.superficie).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AddModeToggle(
            checked = state.settings.addMode,
            onChange = { vm.setAddMode(it) },
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(visible = selected != null) {
            Row {
                IconButton(onClick = { selected?.let { s -> vm.edit { rotate90(s.id, clockwise = true) } } }) {
                    Icon(BkmIcons.GirarDireita, contentDescription = "Girar carimbo 90° à direita")
                }
                IconButton(onClick = { vm.edit { deleteSelected() } }) {
                    Icon(BkmIcons.Excluir, contentDescription = "Excluir carimbo selecionado")
                }
            }
        }
        BotaoPrincipal("Assinar", onClick = { vm.startSigning() }, enabled = state.sign is SignUi.Idle || state.sign is SignUi.Failed)
    }
}

/** Alternância "Adicionar assinaturas" (modo de inserção contínua por duplo toque). */
@Composable
private fun AddModeToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = BkmTheme.colors
    Surface(
        onClick = { onChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        color = if (checked) colors.superficieElevada else colors.superficie,
        contentColor = colors.texto,
        border = BorderStroke(1.dp, if (checked) colors.destaque else colors.bordaControle),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "ativado" else "desativado"
            },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(BkmIcons.Carimbo, contentDescription = null, modifier = Modifier.size(22.dp), tint = if (checked) colors.destaque else colors.texto)
            Spacer(Modifier.width(8.dp))
            Text("Adicionar assinaturas", style = MaterialTheme.typography.labelMedium, maxLines = 2, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.acao, checkedThumbColor = colors.sobreAcao),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Controles contextuais do carimbo selecionado: tamanho e rotação. */
@Composable
private fun StampControls(vm: AppViewModel, stamp: Stamp, page: PageGeometry) {
    val colors = BkmTheme.colors
    var editMm by remember { mutableStateOf(false) }
    var editAngle by remember { mutableStateOf(false) }
    Surface(color = colors.superficie) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Rotulo("Tamanho", Modifier.width(76.dp))
                Slider(
                    value = (stamp.widthFraction * 100).toFloat().coerceIn(2f, 60f),
                    onValueChange = { v -> vm.edit { beginGesture(); setWidthFraction(stamp.id, v / 100.0) } },
                    onValueChangeFinished = { vm.edit { endGesture() } },
                    valueRange = 2f..60f,
                    colors = SliderDefaults.colors(thumbColor = colors.acao, activeTrackColor = colors.destaque),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { editMm = true }) {
                    Text("${formatMm(stamp.widthMm(page))} mm · ${(stamp.widthFraction * 100).roundToInt()}%", color = colors.textoDestaque)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Rotulo("Rotação", Modifier.width(76.dp))
                IconButton(onClick = { vm.edit { rotate90(stamp.id, clockwise = false) } }) { Icon(BkmIcons.GirarEsquerda, "Girar 90° à esquerda") }
                Slider(
                    value = stamp.rotationDeg.toFloat(),
                    onValueChange = { v -> vm.edit { beginGesture(); setRotation(stamp.id, StampRules.snapRotation(v.toDouble())) } },
                    onValueChangeFinished = { vm.edit { endGesture() } },
                    valueRange = 0f..359f,
                    colors = SliderDefaults.colors(thumbColor = colors.acao, activeTrackColor = colors.destaque),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.edit { rotate90(stamp.id, clockwise = true) } }) { Icon(BkmIcons.GirarDireita, "Girar 90° à direita") }
                TextButton(onClick = { editAngle = true }) { Text("${stamp.rotationDeg.roundToInt()}°", color = colors.textoDestaque) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallAction(BkmIcons.Duplicar, "Duplicar") { vm.edit { duplicate(stamp.id) } }
                SmallAction(BkmIcons.Encaixar, "Caber na folha") { vm.edit { fitInsidePage(stamp.id) } }
                SmallAction(BkmIcons.Restaurar, "Padrão") { vm.resetDefaults() }
                SmallAction(BkmIcons.GirarDireita, "Mesma rotação em todos") { vm.edit { applyRotationToAll(stamp.id) } }
            }
        }
    }
    if (editMm) {
        NumberDialog(
            title = "Largura do carimbo",
            label = "Milímetros",
            initial = formatMm(stamp.widthMm(page)),
            hint = "Folha com ${formatMm(page.widthMm)} mm de largura",
            allowZeroOrNegative = false,
            onConfirm = { mm -> vm.edit { setWidthMm(stamp.id, mm) }; editMm = false },
            onDismiss = { editMm = false },
        )
    }
    if (editAngle) {
        NumberDialog(
            title = "Ângulo do carimbo",
            label = "Graus (sentido horário)",
            initial = stamp.rotationDeg.roundToInt().toString(),
            hint = null,
            allowZeroOrNegative = true,
            onConfirm = { deg -> vm.edit { setRotation(stamp.id, deg) }; editAngle = false },
            onDismiss = { editAngle = false },
        )
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = BkmTheme.colors.texto)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = BkmTheme.colors.texto, maxLines = 1)
    }
}

@Composable
private fun NumberDialog(
    title: String,
    label: String,
    initial: String,
    hint: String?,
    allowZeroOrNegative: Boolean,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val value = text.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = value == null,
                )
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = BkmTheme.colors.textoSuave, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            val ok = value != null && (allowZeroOrNegative || value > 0)
            TextButton(onClick = { value?.let(onConfirm) }, enabled = ok) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PagePicker(pages: List<PageGeometry>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ir para a página") },
        text = {
            LazyColumn {
                items(pages) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(p.index) }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Página ${p.index + 1}", style = MaterialTheme.typography.bodyLarge)
                        Text(p.describe() + if (p.isLandscape) " · paisagem" else "", color = BkmTheme.colors.textoSuave, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(color = BkmTheme.colors.linha)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

/** Diálogos do fluxo de assinatura. Nunca afirmam sucesso: o sucesso é a tela de saída, após a verificação. */
@Composable
private fun SigningDialogs(vm: AppViewModel, sign: SignUi) {
    when (sign) {
        SignUi.Idle -> Unit
        SignUi.Authorizing -> ProgressDialog("Autorize no SafeID", "Confirme o uso do certificado na página segura do provedor. O PIN é digitado lá, nunca neste app.") {
            TextButton(onClick = { vm.cancelSigning() }) { Text("Cancelar") }
        }
        is SignUi.Working -> ProgressDialog("Assinando", sign.step + "…", null)
        is SignUi.ChooseCertificate -> CertificateChooser(sign.options, onPick = { vm.chooseCertificate(it) }, onDismiss = { vm.dismissSignError() })
        is SignUi.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissSignError() },
            icon = { Icon(BkmIcons.Alerta, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Documento não assinado") },
            text = { Text(sign.message) },
            confirmButton = {
                if (sign.canRetry) {
                    TextButton(onClick = { vm.dismissSignError(); vm.startSigning() }) { Text("Tentar de novo") }
                } else {
                    TextButton(onClick = { vm.dismissSignError() }) { Text("Entendi") }
                }
            },
            dismissButton = {
                if (sign.canRetry) TextButton(onClick = { vm.dismissSignError() }) { Text("Fechar") }
            },
        )
    }
}

@Composable
private fun ProgressDialog(title: String, text: String, action: (@Composable () -> Unit)?) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(color = BkmTheme.colors.destaque, modifier = Modifier.size(28.dp))
                Text(text)
            }
        },
        confirmButton = { action?.invoke() },
    )
}

@Composable
private fun CertificateChooser(options: List<CloudCertificate>, onPick: (CloudCertificate) -> Unit, onDismiss: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("pt-BR")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escolha o certificado") },
        text = {
            LazyColumn {
                items(options) { c ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 12.dp)) {
                        Text(c.info.holderName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(c.info.issuerName, "válido até " + fmt.format(c.info.notAfter)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = BkmTheme.colors.textoSuave,
                        )
                    }
                    HorizontalDivider(color = BkmTheme.colors.linha)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
