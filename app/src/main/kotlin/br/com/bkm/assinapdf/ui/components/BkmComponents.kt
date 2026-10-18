package br.com.bkm.assinapdf.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.bkm.assinapdf.ui.theme.BkmIcons
import br.com.bkm.assinapdf.ui.theme.BkmTheme

private val BotaoShape = RoundedCornerShape(12.dp)

/** Botão principal do design system: fundo `acao`, texto `sobre-acao`, cantos de 12 dp e seta opcional. */
@Composable
fun BotaoPrincipal(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    seta: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = BotaoShape,
        colors = ButtonDefaults.buttonColors(containerColor = BkmTheme.colors.acao, contentColor = BkmTheme.colors.sobreAcao),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (seta) {
            Spacer(Modifier.width(12.dp))
            Icon(BkmIcons.Seta, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** Botão secundário (contorno) com borda `borda-controle`. */
@Composable
fun BotaoContorno(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = BotaoShape,
        border = BorderStroke(1.dp, BkmTheme.colors.bordaControle),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = BkmTheme.colors.texto),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun BotaoLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, color = BkmTheme.colors.textoDestaque, style = MaterialTheme.typography.labelLarge)
    }
}

/** Rótulo/sobretítulo em caixa alta e espaçado (`rotulo`). */
@Composable
fun Rotulo(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = BkmTheme.colors.textoSuave, modifier = modifier)
}

/** Selo em pílula (PhaseTag). */
@Composable
fun Selo(text: String, destaque: Boolean = false, modifier: Modifier = Modifier) {
    val c = BkmTheme.colors
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (destaque) c.acao else c.superficie,
        contentColor = if (destaque) c.sobreAcao else c.texto,
        border = if (destaque) null else BorderStroke(1.dp, c.bordaControle),
        modifier = modifier,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Aviso em caixa (pêssego com texto marinho para alerta; azul-gelo para informação). */
@Composable
fun Aviso(text: String, alerta: Boolean = false, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = BkmTheme.colors
    Surface(
        color = if (alerta) MaterialTheme.colorScheme.errorContainer else c.superficieElevada,
        contentColor = if (alerta) MaterialTheme.colorScheme.onErrorContainer else c.texto,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (alerta) BkmIcons.Alerta else BkmIcons.Escudo, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}
