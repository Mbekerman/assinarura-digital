package br.com.bkm.assinapdf.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import br.com.bkm.assinapdf.ui.AppRoot
import br.com.bkm.assinapdf.ui.AppViewModel
import br.com.bkm.assinapdf.ui.theme.AssinaPdfTheme

/**
 * Única Activity. Recebe PDFs por ACTION_SEND/ACTION_VIEW (inclusive com o app já aberto, via
 * onNewIntent) e o retorno da autorização do SafeID.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory }

    /** Acesso para testes instrumentados. */
    internal val appViewModel: AppViewModel get() = viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Em recriação (rotação), o ViewModel já tem o documento; não reprocessa o mesmo Intent.
        if (savedInstanceState == null) viewModel.handleIntent(intent)
        setContent {
            AssinaPdfTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResumed()
    }
}
