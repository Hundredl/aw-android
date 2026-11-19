package net.activitywatch.android.ledger

import android.app.Activity
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors

/** Recognizes only the single image the user explicitly shares or selects. */
class OcrPreviewActivity : Activity() {
    private val decoder = Executors.newSingleThreadExecutor()
    private lateinit var choose: Button
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        panel.addView(TextView(this).apply {
            text = "截图文字"
            textSize = 26f
        })
        panel.addView(TextView(this).apply {
            text = "在手机上提取中文和英文。结果仅在这个页面显示，不写入活动记录。"
            textSize = 16f
        })
        choose = Button(this).apply {
            text = "选择截图"
            setOnClickListener {
                startActivityForResult(
                    Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"),
                    PICK_IMAGE
                )
            }
        }
        panel.addView(choose)
        result = TextView(this).apply {
            textSize = 17f
            isSaveEnabled = false
            setTextIsSelectable(true)
        }
        panel.addView(result)
        setContentView(ScrollView(this).apply {
            addView(panel)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        if (intent.action == Intent.ACTION_SEND) {
            val image = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
            if (image != null) recognize(image) else result.text = "请分享一张截图。"
        }
    }

    @Deprecated("Framework activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let(::recognize)
        }
    }

    private fun recognize(uri: Uri) {
        if (uri.scheme != "content") {
            result.text = "请选择手机上的图片。"
            return
        }
        choose.isEnabled = false
        result.text = "正在识别…"
        decoder.execute {
            val bitmap = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_IMAGE_EDGE) {
                        val scale = MAX_IMAGE_EDGE.toFloat() / longest
                        d.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { showResult("无法读取这张图片，请重新选一张。") }
                return@execute
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    bitmap.recycle()
                    return@runOnUiThread
                }
                val recognizer = try {
                    TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                } catch (_: RuntimeException) {
                    bitmap.recycle()
                    showResult("无法启动文字识别。请退出此页后重试。")
                    return@runOnUiThread
                }
                val recognition = try {
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                } catch (_: RuntimeException) {
                    recognizer.close()
                    bitmap.recycle()
                    showResult("识别失败，请换一张清晰的截图。")
                    return@runOnUiThread
                }
                recognition.addOnCompleteListener(mainExecutor) { task ->
                    recognizer.close()
                    bitmap.recycle()
                    showResult(
                        if (task.isSuccessful) task.result.text.ifBlank { "没有识别到文字。" }
                        else "识别失败，请换一张清晰的截图。"
                    )
                }
            }
        }
    }

    private fun showResult(value: String) {
        if (isDestroyed || isFinishing) return
        result.text = value
        choose.isEnabled = true
    }

    override fun onDestroy() {
        decoder.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val PICK_IMAGE = 1
        const val MAX_IMAGE_EDGE = 2560
    }
}
