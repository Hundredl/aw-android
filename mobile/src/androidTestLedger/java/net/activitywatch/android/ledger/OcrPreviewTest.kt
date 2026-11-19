package net.activitywatch.android.ledger

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OcrPreviewTest {
    @Test fun recognizesChineseAndEnglishFromSharedImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "shared-exports").apply { mkdirs() }
        val image = File.createTempFile("ocr-synthetic-", ".png", dir)
        try {
            val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 42f
                }
                drawText("今天阅读项目文档", 64f, 440f, paint)
                drawText("明天下午开会", 64f, 660f, paint)
                drawText("Project Notes", 64f, 910f, paint)
            }
            try {
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", image)
            val intent = Intent(Intent.ACTION_SEND)
                .setClass(context, OcrPreviewActivity::class.java)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val start = SystemClock.elapsedRealtime()
            ActivityScenario.launch<OcrPreviewActivity>(intent).use { scenario ->
                var recognized = false
                var preview = ""
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (!recognized && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<View>(android.R.id.content)
                        preview = visibleText(root).joinToString("\n")
                        val normalized = preview.filterNot { it.isWhitespace() }
                        recognized = listOf("今天阅读项目文档", "明天下午开会", "ProjectNotes")
                            .all { normalized.contains(it, ignoreCase = true) }
                    }
                    if (!recognized) SystemClock.sleep(100)
                }
                assertTrue("Expected the fixture phrases. Synthetic preview: $preview", recognized)
                println("OCR share to preview: ${SystemClock.elapsedRealtime() - start} ms")
            }
        } finally {
            image.delete()
        }
    }

    private fun visibleText(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { visibleText(view.getChildAt(it)) }
        else -> emptyList()
    }
}
