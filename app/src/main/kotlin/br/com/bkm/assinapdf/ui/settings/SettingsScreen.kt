package br.com.bkm.assinapdf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.bkm.assinapdf.BuildConfig
import br.com.bkm.assinapdf.ui.AppUiState
import br.com.bkm.assinapdf.ui.AppViewModel
import br.com.bkm.assinapdf.ui.components.Aviso
import br.com.bkm.assinapdf.ui.components.BotaoContorno
import br.com.bkm.assinapdf.ui.components.Rotulo
import br.com.bkm.assinapdf.ui.theme.BkmIcons
import br.com.bkm.assinapdf.ui.theme.BkmTheme
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, state: AppUiState) {
    val colors = BkmTheme.colors
    val s = state.settings
    var width by remember(s.defaultWidthFraction) { mutableFloatStateOf((s.defaultWidthFraction * 100).toFloat()) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { vm.closeSettings() }) { Icon(BkmIcons.Voltar, "Voltar") } },
            title = { Text("Configurações", style = MaterialTheme.typography.titleMedium) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.superficie),
        )
        HorizontalDivider(color = colors.linha)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Rotulo("Carimbo")
            Text("Tamanho padrão: ${width.roundToInt()}% da largura da folha", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Em A4 (210 mm) dá ${(210 * width / 100).roundToInt()} mm; em A1 (594 mm), ${(594 * width / 100).roundToInt()} mm; em A0 (841 mm), ${(841 * width / 100).roundToInt()} mm.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textoSuave,
            )
            Slider(
                value = width,
                onValueChange = { width = it.roundToInt().toFloat() },
                onValueChangeFinished = { vm.setDefaultWidth(width / 100.0) },
                valueRange = 5f..50f,
                colors = SliderDefaults.colors(thumbColor = colors.acao, activeTrackColor = colors.destaque),
            )
            ToggleRow("Fundo branco atrás do carimbo", "Melhora a leitura sobre desenhos. Desligado, o fundo fica transparente.", s.whiteBackground) { vm.setWhiteBackground(it) }
            ToggleRow("Mostrar medidas do carimbo", "Largura em milímetros, percentual da folha e ângulo ao selecionar.", s.showMeasurements) { vm.setShowMeasurements(it) }

            HorizontalDivider(color = colors.linha)
            Rotulo("Certificado SafeID")
            if (state.signingConfigured) {
                Text("Integração configurada", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Servidor: ${BuildConfig.SAFEID_BASE_URL}\nTroca do código: ${if (BuildConfig.SAFEID_TOKEN_MODE == "DIRECT_PKCE") "direta (PKCE)" else "serviço intermediário"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textoSuave,
                )
                Text(
                    if (state.sessionActive) "Há uma sessão autorizada ativa neste aparelho." else "Nenhuma sessão ativa. A autorização é pedida ao assinar.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                BotaoContorno("Encerrar sessão do SafeID", onClick = { vm.disconnectSafeId() }, enabled = state.sessionActive, modifier = Modifier.fillMaxWidth())
            } else {
                Aviso(state.signingProblem ?: "Integração não configurada.", alerta = true)
            }
            Text(
                "O PIN do certificado é digitado somente na página segura do provedor. Este app não guarda PIN, senha nem chave privada; envia ao SafeID apenas o hash a ser assinado.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textoSuave,
            )

            HorizontalDivider(color = colors.linha)
            Rotulo("Armazenamento")
            BotaoContorno("Limpar arquivos temporários", onClick = { vm.cleanTemporaryFiles() }, modifier = Modifier.fillMaxWidth())
            Text("Versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = colors.textoSuave)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = BkmTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textoSuave)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = colors.acao, checkedThumbColor = colors.sobreAcao))
    }
}
