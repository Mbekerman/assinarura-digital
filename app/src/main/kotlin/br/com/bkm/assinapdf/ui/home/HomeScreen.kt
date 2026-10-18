package br.com.bkm.assinapdf.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.bkm.assinapdf.ui.AppUiState
import br.com.bkm.assinapdf.ui.AppViewModel
import br.com.bkm.assinapdf.ui.components.Aviso
import br.com.bkm.assinapdf.ui.components.BotaoPrincipal
import br.com.bkm.assinapdf.ui.components.Rotulo
import br.com.bkm.assinapdf.ui.components.Selo
import br.com.bkm.assinapdf.ui.theme.BkmIcons
import br.com.bkm.assinapdf.ui.theme.BkmTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: AppViewModel, state: AppUiState) {
    val colors = BkmTheme.colors
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openUri(uri)
    }
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val symbol = remember { BkmIcons.simboloCasa(BkmIcons.gradienteMarca) }
                Image(rememberVectorPainter(symbol), contentDescription = null, modifier = Modifier.size(width = 22.dp, height = 24.dp))
                Spacer(Modifier.width(12.dp))
                Rotulo("Assinatura digital ICP-Brasil", Modifier.weight(1f))
                IconButton(onClick = { vm.openSettings() }) { Icon(BkmIcons.Ajustes, contentDescription = "Configurações") }
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
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.resume(r.id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(BkmIcons.Documento, contentDescription = null, tint = colors.destaque, modifier = Modifier.size(24.dp))
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
}
