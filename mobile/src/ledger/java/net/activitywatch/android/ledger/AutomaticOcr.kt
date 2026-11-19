package net.activitywatch.android.ledger

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** Samples the window after an opted-in page settles. Only recognized text reaches storage. */
internal class AutomaticOcr(
    private val service: AccessibilityService,
    private val store: LedgerStore
) {
    private data class Target(val windowId: Int, val packageName: String)
    private data class Request(val target: Target, val generation: Long, val revision: Long, val epoch: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val capture = Runnable { captureWindow() }
    private var inFlight = false
    private var epoch = 0L
    private var lastAttempt = -INTERVAL_MS
    private var lastHash = ""
    private var lastSaved = 0L

    fun onEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) epoch++
        if (!isEnabled() || packageName == null || !store.canOcrPackage(packageName)) {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handler.removeCallbacks(capture)
            }
            return
        }
        schedule()
    }

    fun cancel() {
        epoch++
        handler.removeCallbacks(capture)
    }

    private fun schedule() {
        handler.removeCallbacks(capture)
        val delay = maxOf(SETTLE_MS, INTERVAL_MS - (SystemClock.elapsedRealtime() - lastAttempt))
        handler.postDelayed(capture, delay)
    }

    private fun isEnabled(): Boolean =
        LedgerService.running && store.active() && store.prefs.getBoolean("automatic_ocr", false)

    private fun target(): Target? {
        if (!isEnabled() || !service.getSystemService(PowerManager::class.java).isInteractive ||
            service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        ) return null
        val root = service.rootInActiveWindow ?: return null
        try {
            val packageName = root.packageName?.toString() ?: return null
            if (!store.canOcrPackage(packageName)) return null

            val windows = service.windows
            val canCaptureWindow = try {
                windows.any { it.id == root.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION } &&
                    windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            } finally {
                windows.forEach { it.recycle() }
            }
            if (!canCaptureWindow || !safeTree(root)) return null
            return Target(root.windowId, packageName)
        } finally {
            root.recycle()
        }
    }

    private fun safeTree(root: AccessibilityNodeInfo): Boolean {
        var seen = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++seen > 600 || depth > 60) return false
            if (node.isVisibleToUser) {
                if (node.isPassword || node.isAccessibilityDataSensitive || node.isEditable && node.isFocused) {
                    return false
                }
                if (sequenceOf(node.text, node.contentDescription, node.viewIdResourceName)
                        .any { it != null && ContentPrivacy.blocks(it.toString()) }
                ) return false
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    if (!visit(child, depth + 1)) return false
                } finally {
                    child.recycle()
                }
            }
            return true
        }
        return visit(root, 0)
    }

    private fun captureWindow() {
        if (inFlight) {
            schedule()
            return
        }
        val candidate = readableTarget() ?: return
        val request = Request(candidate, store.generation(), store.prefs.getLong("automatic_ocr_revision", 0), epoch)
        lastAttempt = SystemClock.elapsedRealtime()
        inFlight = true
        try {
            service.takeScreenshotOfWindow(
                candidate.windowId, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bitmap = try {
                            screenshot.hardwareBuffer.use { buffer ->
                                if (!stillCurrent(request)) return@use null
                                val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: return@use null
                                try {
                                    wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    wrapped.recycle()
                                }
                            }
                        } catch (_: RuntimeException) {
                            null
                        }
                        if (bitmap == null) inFlight = false else recognize(bitmap, request)
                    }
                    // Android's screenshot protection is authoritative. Never fall back to whole-screen capture.
                    override fun onFailure(errorCode: Int) {
                        inFlight = false
                    }
                }
            )
        } catch (_: RuntimeException) {
            inFlight = false
        }
    }

    private fun recognize(bitmap: Bitmap, request: Request) {
        val recognizer = try {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        } catch (_: RuntimeException) {
            bitmap.recycle()
            inFlight = false
            return
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnCompleteListener(service.mainExecutor) { task ->
                    try {
                        if (task.isSuccessful && stillCurrent(request)) {
                            saveText(task.result.text.trim(), request)
                        }
                    } finally {
                        recognizer.close()
                        bitmap.recycle()
                        inFlight = false
                    }
                }
        } catch (_: RuntimeException) {
            recognizer.close()
            bitmap.recycle()
            inFlight = false
        }
    }

    private fun readableTarget(): Target? = try {
        target()
    } catch (_: RuntimeException) {
        null
    }

    private fun stillCurrent(request: Request): Boolean =
        request.epoch == epoch && request.generation == store.generation() &&
            request.revision == store.prefs.getLong("automatic_ocr_revision", 0) &&
            readableTarget() == request.target

    private fun saveText(text: String, request: Request) {
        if (text.isBlank() || ContentPrivacy.blocks(text)) return
        val target = request.target
        val hash = LedgerStore.hash(target.packageName + "|" + text)
        val elapsed = SystemClock.elapsedRealtime()
        if (hash == lastHash && elapsed - lastSaved < 60_000) return
        val now = System.currentTimeMillis()
        val packageManager = service.packageManager
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(target.packageName, 0)).toString()
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            target.packageName
        }
        val event = store.event(
            "content", target.packageName, appName, now, now + 1, "$appName · 截图文字", text.take(6000)
        )
        if (store.addLocalOcr(event, request.generation, request.revision)) {
            lastHash = hash
            lastSaved = elapsed
        }
    }

    private companion object {
        const val SETTLE_MS = 1_500L
        const val INTERVAL_MS = 15_000L
    }
}
