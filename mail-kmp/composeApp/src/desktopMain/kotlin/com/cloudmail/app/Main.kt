package com.cloudmail.app
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import com.cloudmail.app.presentation.App
fun main() = application {
    val viewModel = remember { MailViewModel() }
    DisposableEffect(viewModel) { onDispose { viewModel.dispose() } }
    Window(onCloseRequest = ::exitApplication, title = "Cloud Mail") { App(viewModel) }
}
