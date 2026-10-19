package com.cloudmail.app

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** Exercises pending system-picker ownership without a device or real private files. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAttachmentLifecycleTest {
    private class Launcher<I> : ActivityResultLauncher<I>() {
        var launches = 0
        override fun launch(input: I, options: ActivityOptionsCompat?) { launches++ }
        override fun unregister() {}
        override fun getContract(): ActivityResultContract<I, *> = object : ActivityResultContract<I, Unit>() {
            override fun createIntent(context: Context, input: I): Intent = error("No real Android picker in a unit test")
            override fun parseResult(resultCode: Int, intent: Intent?) {}
        }
    }

    @Test fun logoutCancelsPendingPickerAndAllowsFreshSessionPicker() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pick = Launcher<Array<String>>()
        AndroidFiles.bind(pick, Launcher())
        try {
            val first = async { AndroidFiles.pick() }
            runCurrent(); assertEquals(1, pick.launches); assertFalse(first.isCompleted)
            AndroidFiles.clearSensitiveOperations()
            runCurrent(); assertTrue(first.isCancelled)
            val second = async { AndroidFiles.pick() }
            runCurrent(); assertEquals(2, pick.launches); assertFalse(second.isCompleted)
            AndroidFiles.clearSensitiveOperations()
            runCurrent(); assertTrue(second.isCancelled)
        } finally { AndroidFiles.unbind(true); Dispatchers.resetMain() }
    }

    @Test fun finishingActivityCancelsPendingPicker() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        AndroidFiles.bind(Launcher(), Launcher())
        try {
            val operation = async { AndroidFiles.pick() }
            runCurrent(); assertFalse(operation.isCompleted)
            AndroidFiles.unbind(true)
            runCurrent(); assertTrue(operation.isCancelled)
        } finally { AndroidFiles.unbind(true); Dispatchers.resetMain() }
    }
}
