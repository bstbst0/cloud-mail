@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.cloudmail.app

import kotlinx.cinterop.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses security-scoped document URLs; no entire-library entitlement or filesystem permission. */
internal object IosFiles {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private var continuation: CancellableContinuation<List<OutgoingAttachment>>? = null
    private var saveContinuation: CancellableContinuation<Unit>? = null
    private var sharing: UIActivityViewController? = null
    private var preview: UIDocumentInteractionController? = null
    private var pickPicker: UIDocumentPickerViewController? = null
    private var savePicker: UIDocumentPickerViewController? = null
    private val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            val waiting = continuation; continuation = null; pickPicker = null
            if (waiting == null) { saveContinuation?.let { if (it.isActive) it.resume(Unit) }; saveContinuation = null; savePicker = null; return }
            try {
                val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>(); require(urls.size <= 10) { "Select no more than 10 attachments." }
                var total = 0
                val files = urls.map { url ->
                    val scoped = url.startAccessingSecurityScopedResource()
                    try {
                        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(url.path ?: error("Invalid document URL."), null)
                        val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: error("Unable to read attachment size.")
                        require(size <= MAX_BYTES) { "Each attachment must be at most 10 MB." }
                        val stream = NSInputStream.inputStreamWithURL(url) ?: error("Unable to read selected document.")
                        val data = NSMutableData(); val buffer = ByteArray(8192)
                        stream.open()
                        try {
                            buffer.usePinned { pinned ->
                                while (true) {
                                    val count = stream.read(pinned.addressOf(0).reinterpret(), maxLength = buffer.size.toULong())
                                    if (count == 0L) break
                                    check(count > 0) { "Unable to read selected document." }
                                    require(data.length + count.toULong() <= MAX_BYTES.toULong() && total + data.length.toInt() + count <= MAX_BYTES) { "Combined attachments must be at most 10 MB." }
                                    data.appendBytes(pinned.addressOf(0), length = count.toULong())
                                }
                            }
                        } finally { stream.close() }
                        total += data.length.toInt()
                        OutgoingAttachment(safeName(url.lastPathComponent ?: "attachment"), mime(url.pathExtension ?: ""), data.base64EncodedStringWithOptions(0u))
                    } finally { if (scoped) url.stopAccessingSecurityScopedResource() }
                }
                if (waiting.isActive) waiting.resume(files)
            } catch (error: Throwable) { if (waiting.isActive) waiting.resumeWithException(error) }
        }
        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            continuation?.let { if (it.isActive) it.resume(emptyList()) }; continuation = null; pickPicker = null
            saveContinuation?.let { if (it.isActive) it.resume(Unit) }; saveContinuation = null; savePicker = null
        }
    }
    private val previewDelegate = object : NSObject(), UIDocumentInteractionControllerDelegateProtocol {
        override fun documentInteractionControllerViewControllerForPreview(controller: UIDocumentInteractionController): UIViewController = presenter()
    }
    fun presenter(): UIViewController {
        val windows = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
        var root = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController ?: error("No active window is available.")
        while (root.presentedViewController != null) root = root.presentedViewController!!
        return root
    }
    suspend fun pick(): List<OutgoingAttachment> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { waiting ->
            if (continuation != null || saveContinuation != null) { waiting.resumeWithException(IllegalStateException("A file picker is already open.")); return@suspendCancellableCoroutine }
            continuation = waiting
            val picker = UIDocumentPickerViewController(documentTypes = listOf("public.data", "public.content"), inMode = UIDocumentPickerMode.UIDocumentPickerModeImport)
            picker.allowsMultipleSelection = true; picker.delegate = delegate; pickPicker = picker
            waiting.invokeOnCancellation { if (continuation === waiting) continuation = null }
            presenter().presentViewController(picker, animated = true, completion = null)
        }
    }
    suspend fun save(name: String, mimeType: String, bytes: ByteArray, action: String) = withContext(Dispatchers.Main) {
        check(continuation == null && saveContinuation == null) { "Close the other document picker before opening an attachment." }
        check(IosPlatform().secret("session") != null) { "Sign in before opening an attachment." }
        require(bytes.size <= 25 * 1024 * 1024) { "Attachment exceeds 25 MB." }
        val directory = "${NSTemporaryDirectory()}cloudmail-attachments"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication), error = null)
        val path = "$directory/${NSUUID().UUIDString}-${safeName(name)}"
        check(bytes.nsData().writeToFile(path, atomically = true)) { "Unable to save attachment." }
        val url = NSURL.fileURLWithPath(path)
        when (action) {
            "download" -> suspendCancellableCoroutine<Unit> { waiting ->
                if (saveContinuation != null) { waiting.resumeWithException(IllegalStateException("Another attachment is being saved.")); return@suspendCancellableCoroutine }
                saveContinuation = waiting
                val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
                picker.delegate = delegate; savePicker = picker
                waiting.invokeOnCancellation { if (saveContinuation === waiting) saveContinuation = null }
                presenter().presentViewController(picker, animated = true, completion = null)
            }
            "share" -> {
                val activity = UIActivityViewController(activityItems = listOf(url), applicationActivities = null).also { sharing = it }
                activity.popoverPresentationController?.sourceView = presenter().view
                presenter().presentViewController(activity, animated = true, completion = null)
            }
            else -> {
                preview = UIDocumentInteractionController.interactionControllerWithURL(url).apply { delegate = previewDelegate }
                if (preview?.presentPreviewAnimated(true) != true) {
                    val activity = UIActivityViewController(activityItems = listOf(url), applicationActivities = null).also { sharing = it }
                    activity.popoverPresentationController?.sourceView = presenter().view
                    presenter().presentViewController(activity, animated = true, completion = null)
                }
            }
        }
    }
    fun clearTemporaryFiles() {
        continuation?.cancel(); continuation = null
        saveContinuation?.cancel(); saveContinuation = null
        pickPicker?.dismissViewControllerAnimated(false, completion = null); pickPicker = null
        savePicker?.dismissViewControllerAnimated(false, completion = null); savePicker = null
        preview?.dismissPreviewAnimated(false); preview = null
        sharing?.dismissViewControllerAnimated(false, completion = null); sharing = null
        NSFileManager.defaultManager.removeItemAtPath("${NSTemporaryDirectory()}cloudmail-attachments", null)
    }
    private fun safeName(name: String) = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}<>:\"|?*]"), "_").take(180).let { if (it.isBlank() || it == "." || it == "..") "attachment" else it }
    private fun mime(extension: String): String = UTType.typeWithFilenameExtension(extension)?.preferredMIMEType ?: "application/octet-stream"
}
