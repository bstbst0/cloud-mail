package com.cloudmail.app

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Activity Result registration survives configuration changes; content streams are bounded. */
internal object AndroidFiles {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    private var pickLauncher: ActivityResultLauncher<Array<String>>? = null
    data class SaveTarget(val name: String, val mime: String)
    private var saveLauncher: ActivityResultLauncher<SaveTarget>? = null
    private var picker: CancellableContinuation<List<OutgoingAttachment>>? = null
    private data class Save(val bytes: ByteArray, val continuation: CancellableContinuation<Unit>)
    private var saving: Save? = null
    @Volatile private var generation = 0L
    /** Logout invalidates captured IO work as well as currently visible pickers. */
    fun clearSensitiveOperations() {
        generation++
        picker?.cancel(); picker = null
        saving?.continuation?.cancel(); saving = null
    }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun bind(pick: ActivityResultLauncher<Array<String>>, save: ActivityResultLauncher<SaveTarget>) { pickLauncher = pick; saveLauncher = save }
    fun unbind(finishing: Boolean) {
        pickLauncher = null; saveLauncher = null
        if (finishing) { picker?.cancel(); picker = null; saving?.continuation?.cancel(); saving = null }
    }
    fun safeName(value: String) = value.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}<>:\"|?*]"), "_").take(180).let { if (it.isBlank() || it == "." || it == "..") "attachment" else it }
    fun safeMime(value: String) = if (Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+").matches(value)) value else "application/octet-stream"
    suspend fun pick(): List<OutgoingAttachment> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            if (picker != null || saving != null || pickLauncher == null) { continuation.resumeWithException(IllegalStateException("The file picker is unavailable. Close the other picker and try again.")); return@suspendCancellableCoroutine }
            picker = continuation; continuation.invokeOnCancellation { if (picker === continuation) picker = null }
            pickLauncher!!.launch(arrayOf("*/*"))
        }
    }
    fun picked(uris: List<Uri>) {
        val continuation = picker ?: return; picker = null
        val started = generation
        io.launch {
            try {
                check(continuation.isActive && generation == started) { "Attachment selection was cancelled." }
                require(uris.size <= 10) { "Select no more than 10 attachments." }
                val resolver = AndroidRuntime.context.contentResolver
                var total = 0
                val files = uris.map { uri ->
                    check(continuation.isActive && generation == started) { "Attachment selection was cancelled." }
                    var name = "attachment"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) name = safeName(cursor.getString(nameIndex) ?: "attachment")
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) require(cursor.getLong(sizeIndex) <= MAX_FILE_BYTES) { "$name exceeds 10 MB." }
                        }
                    }
                    val bytes = resolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { check(continuation.isActive && generation == started) { "Attachment selection was cancelled." }; val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= MAX_FILE_BYTES) { "$name exceeds 10 MB." }; output.write(buffer, 0, count) }
                        output.toByteArray()
                    } ?: error("Unable to read $name.")
                    total += bytes.size; require(total <= MAX_FILE_BYTES) { "Combined attachments must be at most 10 MB." }
                    OutgoingAttachment(name, safeMime(resolver.getType(uri) ?: "application/octet-stream"), Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
                synchronized(AndroidRuntime.sessionLock) {
                    if (continuation.isActive && generation == started) continuation.resume(files)
                    else continuation.cancel()
                }
            } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }
    suspend fun save(name: String, mime: String, bytes: ByteArray) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            if (saving != null || picker != null || saveLauncher == null) { continuation.resumeWithException(IllegalStateException("The save picker is unavailable.")); return@suspendCancellableCoroutine }
            saving = Save(bytes, continuation); continuation.invokeOnCancellation { if (saving?.continuation === continuation) saving = null }
            saveLauncher!!.launch(SaveTarget(safeName(name), safeMime(mime)))
        }
    }
    fun saved(uri: Uri?) {
        val operation = saving ?: return; saving = null
        if (uri == null) { if (operation.continuation.isActive) operation.continuation.resume(Unit); return }
        val started = generation
        io.launch {
            try { synchronized(AndroidRuntime.sessionLock) {
                check(operation.continuation.isActive && generation == started) { "Attachment export was cancelled." }
                AndroidRuntime.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(operation.bytes) } ?: error("Unable to save the attachment.")
                if (operation.continuation.isActive) operation.continuation.resume(Unit)
            } }
            catch (error: Throwable) { if (operation.continuation.isActive) operation.continuation.resumeWithException(error) }
        }
    }
}
