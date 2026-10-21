package com.cloudmail.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient

interface PlatformServices {
    val cacheDispatcher: kotlinx.coroutines.CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Default
    val defaultServerUrl: String get() = ""
    val allowLocalHttp: Boolean get() = false
    val persistentSecureStorage: Boolean
    fun httpClient(): HttpClient
    fun databaseDriver(): SqlDriver
    fun secret(key: String): String?
    fun putSecret(key: String, value: String?)
    fun encrypt(bytes: ByteArray): ByteArray
    fun decrypt(bytes: ByteArray): ByteArray
    fun randomId(): String
    suspend fun pickAttachments(): List<OutgoingAttachment>
    suspend fun saveAttachment(name: String, mimeType: String, bytes: ByteArray, action: String)
    fun notifyMail(messages: List<Message>) {}
    fun configureSync(minutes: Int) {}
}
expect fun createPlatformServices(): PlatformServices
@Composable expect fun HtmlMessage(html: String, loadImages: Boolean, modifier: Modifier = Modifier)

expect fun platformColorScheme(dark: Boolean, dynamic: Boolean): androidx.compose.material3.ColorScheme?

expect fun supportsHtmlRenderer(): Boolean
