package com.cloudmail.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.app.Activity
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cloudmail.app.presentation.App

class MainActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), AndroidFiles::picked)
    private val save = registerForActivityResult(object : ActivityResultContract<AndroidFiles.SaveTarget, Uri?>() {
        override fun createIntent(context: Context, input: AndroidFiles.SaveTarget) = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
    }, AndroidFiles::saved)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private lateinit var mail: MailViewModel
    private var openedId by mutableStateOf<Long?>(null)
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (::mail.isInitialized) runOnUiThread { mail.refresh() } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); AndroidFiles.bind(pick, save)
        mail = ViewModelProvider(this, object : ViewModelProvider.Factory { override fun <T : ViewModel> create(modelClass: Class<T>): T { @Suppress("UNCHECKED_CAST") return MailViewModel(AndroidPlatform(applicationContext)) as T } })[MailViewModel::class.java]
        openedId = intent.getLongExtra("messageId", -1).takeIf { it > 0 }
        connectivity.registerDefaultNetworkCallback(callback)
        setContent {
            val state by mail.state.collectAsState()
            LaunchedEffect(state.session?.userId) {
                if (state.session != null && Build.VERSION.SDK_INT >= 33) {
                    val prefs = getSharedPreferences("notification-permission", MODE_PRIVATE)
                    if (!prefs.getBoolean("asked", false)) { prefs.edit().putBoolean("asked", true).apply(); notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            }
            LaunchedEffect(openedId, state.messages) { openedId?.let { id -> state.messages.find { it.id == id }?.let { mail.select(it); openedId = null } } }
            BackHandler(enabled = state.composer != null || state.selected != null) { if (state.composer != null) mail.closeComposer() else mail.select(null) }
            App(mail)
        }
    }
    override fun onStart() { super.onStart(); AndroidRuntime.foreground = true }
    override fun onStop() { AndroidRuntime.foreground = false; super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openedId = intent.getLongExtra("messageId", -1).takeIf { it > 0 }; mail.refresh() }
    override fun onDestroy() { connectivity.unregisterNetworkCallback(callback); AndroidFiles.unbind(isFinishing); super.onDestroy() }
}
