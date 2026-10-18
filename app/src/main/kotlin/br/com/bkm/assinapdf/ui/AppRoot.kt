package br.com.bkm.assinapdf.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.bkm.assinapdf.ui.editor.EditorScreen
import br.com.bkm.assinapdf.ui.home.HomeScreen
import br.com.bkm.assinapdf.ui.settings.SettingsScreen
import br.com.bkm.assinapdf.ui.signed.OutputScreen
import br.com.bkm.assinapdf.ui.theme.BkmTheme

@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.effectFlow.collect { effect ->
            when (effect) {
                is UiEffect.Message -> snackbar.showSnackbar(effect.text)
                is UiEffect.OpenUrl -> try {
                    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(effect.url))
                } catch (e: ActivityNotFoundException) {
                    vm.onAuthorizationLaunchFailed()
                }
            }
        }
    }

    BackHandler(enabled = state.screen != Screen.HOME) {
        when (state.screen) {
            Screen.SETTINGS -> vm.closeSettings()
            Screen.OUTPUT -> vm.backToEditor()
            Screen.EDITOR -> vm.goHome()
            Screen.HOME -> Unit
        }
    }

    Surface(color = BkmTheme.colors.superficie, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            when (state.screen) {
                Screen.HOME -> HomeScreen(vm, state)
                Screen.EDITOR -> Box(Modifier.statusBarsPadding()) { EditorScreen(vm, state) }
                Screen.OUTPUT -> OutputScreen(vm, state)
                Screen.SETTINGS -> Box(Modifier.statusBarsPadding().navigationBarsPadding()) { SettingsScreen(vm, state) }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
        }
    }
}
