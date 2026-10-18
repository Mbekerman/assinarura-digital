package br.com.bkm.assinapdf.ui.signed

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.bkm.assinapdf.pdf.engine.PdfVerification
import br.com.bkm.assinapdf.sharing.ShareLauncher
import br.com.bkm.assinapdf.ui.AppUiState
import br.com.bkm.assinapdf.ui.AppViewModel
import br.com.bkm.assinapdf.ui.components.Aviso
import br.com.bkm.assinapdf.ui.components.BotaoContorno
import br.com.bkm.assinapdf.ui.components.BotaoLink
import br.com.bkm.assinapdf.ui.components.BotaoPrincipal
import br.com.bkm.assinapdf.ui.components.Rotulo
import br.com.bkm.assinapdf.ui.theme.BkmIcons
import br.com.bkm.assinapdf.ui.theme.BkmTheme

/**
 * Tela de conclusão. "Documento assinado com sucesso" só aparece para um arquivo cuja assinatura
 * foi aplicada pelo SafeID e conferida localmente (integridade do ByteRange e do CMS).
 */
@Composable
fun OutputScreen(vm: AppViewModel, state: AppUiState) {
    val out = state.output ?: return
    val context = LocalContext.current
    val colors = BkmTheme.colors
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            runCatching { ShareLauncher.copyTo(context, out.file, uri) }
                .onSuccess { vm.message("PDF salvo.") }
                .onFailure { vm.message("Não foi possível salvar no local escolhido.") }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            if (out.signed) BkmIcons.Escudo else BkmIcons.Documento,
            contentDescription = null,
            tint = colors.destaque,
            modifier = Modifier.size(48.dp),
        )
        Rotulo(if (out.signed) "Assinatura digital ICP-Brasil" else "Rascunho")
        Text(
            if (out.signed) "Documento assinado com sucesso" else "Rascunho exportado sem assinatura digital",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.texto,
        )
        Text(out.file.name, style = MaterialTheme.typography.bodyLarge, color = colors.textoSuave)
        if (out.signed) {
            val details = listOfNotNull(
                out.holder?.let { "Assinado por $it" },
                out.time?.let { "em $it" },
                out.issuer?.let { "Certificado emitido por $it" },
            )
            if (details.isNotEmpty()) Text(details.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = colors.texto)
            Aviso("Integridade conferida neste aparelho. Para validar a cadeia ICP-Brasil, use o VALIDAR do ITI.")
        } else {
            Aviso("Este arquivo NÃO tem assinatura digital. Use apenas para conferência.", alerta = true)
        }
        Spacer(Modifier.height(8.dp))
        BotaoPrincipal("Compartilhar", onClick = { ShareLauncher.share(context, out.file) }, icon = BkmIcons.Compartilhar, modifier = Modifier.fillMaxWidth())
        BotaoContorno("Salvar PDF", onClick = { saver.launch(out.file.name) }, icon = BkmIcons.Salvar, modifier = Modifier.fillMaxWidth())
        BotaoContorno(
            if (out.signed) "Abrir PDF assinado" else "Abrir rascunho",
            onClick = { if (!ShareLauncher.open(context, out.file)) vm.message("Nenhum app para abrir PDF foi encontrado.") },
            icon = BkmIcons.Documento,
            modifier = Modifier.fillMaxWidth(),
        )
        if (out.signed) {
            BotaoContorno("Verificar assinatura", onClick = { vm.verifyOutput() }, icon = BkmIcons.Escudo, modifier = Modifier.fillMaxWidth())
            BotaoLink("Abrir o VALIDAR do ITI", onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://validar.iti.gov.br"))) }
            })
        }
        Spacer(Modifier.height(8.dp))
        BotaoLink("Voltar ao documento", onClick = { vm.backToEditor() })
        BotaoLink("Início", onClick = { vm.goHome() })
    }

    state.verificationDetails?.let { VerificationDialog(it, onDismiss = { vm.dismissVerification() }) }
}

@Composable
private fun VerificationDialog(v: PdfVerification, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (v.allValid) "Assinatura íntegra" else "Problemas encontrados") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                v.signatures.forEach { s ->
                    Text(
                        buildString {
                            append("Assinatura ${s.index + 1}")
                            s.signerName?.let { append(" · $it") }
                            append(if (s.valid) ": conteúdo íntegro" else ": inválida")
                            if (!s.coversWholeFile) append(" (revisão anterior do documento)")
                            if (s.problems.isNotEmpty()) append("\n" + s.problems.joinToString("; "))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Esta verificação confere a integridade e a correspondência com o certificado. A validade da cadeia ICP-Brasil e a revogação são conferidas no VALIDAR do ITI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BkmTheme.colors.textoSuave,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
