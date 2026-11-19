package net.activitywatch.android.ledger

import android.net.LocalServerSocket
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test

/** Explicit USB setup helper only; never reads or clears activity records. Not part of the app APK. */
class UsbPairing {
    @Test fun localOverview(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val store=LedgerStore(instrumentation.targetContext)
        val before=store.counts().first
        val device=androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        instrumentation.targetContext.startActivity(android.content.Intent(instrumentation.targetContext,LedgerActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val history=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("查看本地历史与统计")),15000) ?: error("Local history entry missing")
        org.junit.Assert.assertNotNull("Local history entry must be visible",history)
        history.click()
        org.junit.Assert.assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(java.time.LocalDate.now().toString()+" · 本地记录")),10000))
        device.findObject(androidx.test.uiautomator.By.text("关闭")).click()
        org.junit.Assert.assertTrue("Read-only history preserves records",store.counts().first>=before)
    }
    @Test(timeout=180000) fun configure(){
        val args=InstrumentationRegistry.getArguments()
        val nonce=args.getString("pair_nonce").orEmpty()
        require(nonce.matches(Regex("[a-f0-9]{64}")))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=LedgerStore(context)
        LocalServerSocket("ledger-usb-pair-$nonce").use { listener ->
            listener.accept().use { socket ->
                socket.soTimeout=20000
                socket.outputStream.write("READY\n".toByteArray());socket.outputStream.flush()
                val bytes=java.io.ByteArrayOutputStream()
                while(true){val b=socket.inputStream.read();if(b<0||b==10)break;require(bytes.size()<16384);bytes.write(b)}
                val body=JSONObject(bytes.toString("UTF-8"))
                require(body.getString("nonce")==nonce)
                val endpoint=java.net.URI(body.getString("endpoint"))
                require(endpoint.scheme=="https"&&endpoint.userInfo==null&&endpoint.query==null&&endpoint.fragment==null&&endpoint.path in listOf("","/"))
                require(body.getString("device_token").length in 32..512)
                require(body.getString("site_token").length in 16..8192)
                store.saveSecret("device_token",body.getString("device_token"))
                store.saveSecret("site_token",body.getString("site_token"))
                check(store.prefs.edit().putString("endpoint",endpoint.toString().trimEnd('/')).putBoolean("sync",true).putInt("sync_interval_minutes",60).putLong("last_sync_attempt",0).commit())
                store.status("云端已配置 · 每小时自动同步；首次连接待验证")
                socket.outputStream.write("{\"configured\":true}\n".toByteArray())
                socket.outputStream.flush()
            }
        }
    }
}
