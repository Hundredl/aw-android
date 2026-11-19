package net.activitywatch.android.ledger

import android.content.Intent
import android.app.UiAutomation
import androidx.test.uiautomator.Configurator
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import android.net.Uri
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PrivateLedgerTest {
 private val inst=InstrumentationRegistry.getInstrumentation()
 private val context=inst.targetContext
 private val store=LedgerStore(context)
 private val device=run{Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);UiDevice.getInstance(inst)}
 private val base="http://127.0.0.1:5173"
 @Before fun isolatedEmulatorOnly(){
  assertEquals("Never run this destructive synthetic-data test on a personal device","1",device.executeShellCommand("getprop ro.kernel.qemu").trim())
  store.dismissPending()
  store.prefs.edit().remove("incognito_content").commit()
 }
 private fun http(path:String,body:JSONObject?=null,cookie:String=""):JSONObject {
  val c=URL(base+path).openConnection() as HttpURLConnection;c.connectTimeout=10000;c.readTimeout=10000
  if(cookie.isNotEmpty())c.setRequestProperty("Cookie",cookie)
  if(body!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray())}}
  try{assertEquals("API request",200,c.responseCode);return JSONObject(c.inputStream.bufferedReader().readText())}finally{c.disconnect()}
 }
 private fun waitFor(message:String,seconds:Int=20,predicate:()->Boolean){val end=System.currentTimeMillis()+seconds*1000;while(System.currentTimeMillis()<end){if(predicate())return;Thread.sleep(250)};assertTrue(message,predicate())}
 private fun fixture(mode:String){context.startActivity(Intent().setClassName("lab.fixture","lab.fixture.FixtureActivity").putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));Thread.sleep(2200)}
 private fun pendingRows():List<JSONObject>{val a=store.pending(10000);return (0 until a.length()).map{a.getJSONObject(it)}}
 private fun contentCount()=pendingRows().count{it.getString("kind")=="content"}
 @Test fun capturePrivacyAndSynchronization(){
  // This test only runs against a loopback preview with synthetic fixtures.
  val login=URL(base+"/signin-with-chatgpt?return_to=/activity").openConnection() as HttpURLConnection
  login.instanceFollowRedirects=false;login.responseCode
  val cookie=login.getHeaderField("Set-Cookie").substringBefore(';');login.disconnect()
  val pair=http("/api/capture",JSONObject().put("action","pair").put("dataset","test").put("name","Android模拟器验收"),cookie)
  store.setActive(false)
  store.saveSecret("device_token",pair.getString("device_token"));store.saveSecret("site_token","")
  store.prefs.edit().putString("endpoint",base).putString("packages","lab.fixture,com.android.chrome").putString("domains","127.0.0.1").putBoolean("content",true).putBoolean("sync",false).commit()
  context.startActivity(Intent(context,LedgerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(700)
  device.executeShellCommand("settings put secure enabled_accessibility_services null")
  Thread.sleep(1500)
  device.executeShellCommand("settings put secure enabled_accessibility_services ${context.packageName}/net.activitywatch.android.ledger.ContentService")
  device.executeShellCommand("settings put secure accessibility_enabled 1")
  waitFor("content service is connected",30){ContentService.connected}
  store.setActive(true);context.startForegroundService(Intent(context,LedgerService::class.java))
  waitFor("foreground notification service is running",30){LedgerService.running}
  val before=contentCount();fixture("public")
  waitFor("positive control must capture visible public Chinese text"){contentCount()>before}
  assertTrue(store.recent().any{it.optString("text").contains("长期记忆")})
  var n=contentCount()
  for(mode in listOf("private","password","secure")){fixture(mode);assertEquals("$mode must not persist content",n,contentCount())}
  val pauseAt=System.currentTimeMillis();store.setActive(false);fixture("second");assertEquals("pause blocks content",n,contentCount())
  val resumeAt=System.currentTimeMillis();store.setActive(true);fixture("second");waitFor("resume captures new public page"){contentCount()>n}
  // A different URL authority prevents normal-page dedup from masking a privacy failure.
  val browserBefore=pendingRows().count{it.optString("text").contains("PUBLIC_BROWSER_CANARY_7319")}
  context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(base+"/capture-fixture.html")).setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  waitFor("Chrome public page captured",40){pendingRows().count{it.optString("text").contains("PUBLIC_BROWSER_CANARY_7319")}>browserBefore}
  val browserCount=contentCount()
  val menu=device.wait(Until.findObject(By.res("com.android.chrome:id/menu_button")),10000)
  assertNotNull("Chrome toolbar",menu);menu.click()
  val incognito=device.wait(Until.findObject(By.text("New Incognito tab")),10000)
  assertNotNull("private tab action",incognito);incognito.click()
  assertTrue("new private tab is ready",device.wait(Until.hasObject(By.desc("Incognito mode")),10000))
  device.waitForIdle(1000)
  val url=device.wait(Until.findObject(By.res("com.android.chrome:id/url_bar")),10000)
  assertNotNull(url);url.click()
  val privateEditor=device.wait(Until.findObject(By.res("com.android.chrome:id/url_bar").focused(true)),10000)
  assertNotNull(privateEditor)
  if(InstrumentationRegistry.getArguments().getString("manual_private")=="1"){
   println("MANUAL_PRIVATE_READY")
   val end=System.currentTimeMillis()+120000
   var verified=false
   while(System.currentTimeMillis()<end){
    val root=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow
    var privateMode=false;var canary=false;var target=false;var focused=false
    fun inspect(n:android.view.accessibility.AccessibilityNodeInfo){
     if(n.isVisibleToUser){
      val text=n.text?.toString().orEmpty();val desc=n.contentDescription?.toString().orEmpty()
      if(desc=="Incognito mode")privateMode=true
      if(text.contains("PUBLIC_BROWSER_CANARY_7319"))canary=true
      if(n.viewIdResourceName=="com.android.chrome:id/url_bar"){target=text.contains("127.0.0.1:5174");focused=n.isFocused}
     }
     for(i in 0 until n.childCount)n.getChild(i)?.let{try{inspect(it)}finally{it.recycle()}}
    }
    root?.let{try{inspect(it)}finally{it.recycle()}}
    java.io.File(context.filesDir,"manual-state.json").writeText(JSONObject().put("private",privateMode).put("canary",canary).put("target",target).put("focused",focused).toString())
    if(privateMode&&canary&&target&&!focused){verified=true;break}
    Thread.sleep(500)
   }
   assertTrue("manual private page positively verified",verified)
  }else{
  privateEditor.text=""
  device.executeShellCommand("input text http://127.0.0.1:5174/")
  waitFor("private URL entered",10){device.findObject(By.res("com.android.chrome:id/url_bar"))?.text?.contains("127.0.0.1:5174")==true}
  val suggestion=device.wait(Until.findObject(By.res("com.android.chrome:id/line_1").textContains("127.0.0.1:5174")),10000)
  assertNotNull("private URL navigation suggestion",suggestion);suggestion.click()
  waitFor("private page URL loaded",20){device.findObject(By.res("com.android.chrome:id/url_bar"))?.text?.contains("127.0.0.1:5174")==true}
  assertTrue("private mode remains active",device.hasObject(By.desc("Incognito mode")))
  assertTrue("private page positive visibility control",device.wait(Until.hasObject(By.textContains("PUBLIC_BROWSER_CANARY_7319")),20000))
  }
  Thread.sleep(7000)
  assertEquals("incognito content not persisted",browserCount,contentCount())
  device.pressHome();Thread.sleep(12000)
  val apps=store.recent().filter{it.getString("kind")=="app"}
  assertTrue("usage sessions recorded",apps.isNotEmpty())
  assertTrue("paused activity not backfilled",apps.all{Instant.parse(it.getString("start")).toEpochMilli()>=resumeAt || Instant.parse(it.getString("stop")).toEpochMilli()<=pauseAt})
  assertTrue("offline queue retained",store.counts().second>0)
  context.startActivity(Intent(context,LedgerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(500)
  context.startForegroundService(Intent(context,LedgerService::class.java).setAction("sync"))
  waitFor("local queue acknowledged",30){store.counts().second==0}
  val date=Instant.ofEpochMilli(System.currentTimeMillis()+28800000).toString().take(10)
  val dashboard=http("/api/capture?date=$date&dataset=test",cookie=cookie)
  val rows=dashboard.getJSONArray("events");assertTrue("end-to-end server timeline",rows.length()>0)
  assertTrue("test area only",dashboard.getString("dataset")=="test");assertFalse(dashboard.getBoolean("writeEnabled"))
  assertFalse("database bytes must not contain visible text",context.getDatabasePath("private-ledger.db").readBytes().toString(Charsets.ISO_8859_1).contains("PUBLIC_TEST"))
  assertFalse("preferences must not expose token",java.io.File(context.applicationInfo.dataDir,"shared_prefs/ledger.xml").readText().contains(pair.getString("device_token")))
  store.setActive(false);context.stopService(Intent(context,LedgerService::class.java))
 }
 @Test fun incognitoPreferenceDefaultsAndPersists(){
  androidx.test.core.app.ActivityScenario.launch(LedgerActivity::class.java).use { scenario ->
   fun views(v:android.view.View):List<android.view.View> = listOf(v) + if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
   fun controls(a:LedgerActivity):List<android.view.View> = views(a.window.decorView)
   scenario.onActivity { a ->
    val controls=controls(a)
    val content=controls.filterIsInstance<android.widget.Switch>().first{it.text.toString().startsWith("允许采集")}
    val incognito=controls.filterIsInstance<android.widget.Switch>().first{it.text.toString().startsWith("记录无痕")}
    assertFalse("new preference defaults off",incognito.isChecked)
    content.isChecked=false;assertFalse("content master disables incognito control",incognito.isEnabled)
    content.isChecked=true;assertTrue(incognito.isEnabled)
    incognito.isChecked=true
    controls.filterIsInstance<android.widget.Button>().first{it.text=="保存设置并暂停"}.performClick()
    assertTrue(store.prefs.getBoolean("incognito_content",false));assertFalse("saving pauses capture",store.active())
   }
   scenario.recreate()
   scenario.onActivity { a ->
    val controls=controls(a)
    val incognito=controls.filterIsInstance<android.widget.Switch>().first{it.text.toString().startsWith("记录无痕")}
    assertTrue("choice survives recreation",incognito.isChecked)
    incognito.isChecked=false
    controls.filterIsInstance<android.widget.Button>().first{it.text=="保存设置并暂停"}.performClick()
    assertFalse(store.prefs.getBoolean("incognito_content",true));assertFalse(store.active())
   }
   scenario.recreate()
   scenario.onActivity { a ->
    assertFalse("off choice survives recreation",controls(a).filterIsInstance<android.widget.Switch>().first{it.text.toString().startsWith("记录无痕")}.isChecked)
   }
  }
 }

 @Test fun discardQueueKeepsReplayTombstone(){
  store.prefs.edit().putString("packages","lab.fixture").commit();store.setActive(true)
  val now=System.currentTimeMillis()
  val event=store.event("content","lab.fixture","测试阅读器",now,now+1,"待丢弃","DISCARD_SYNTHETIC_CANARY")
  store.add(event,store.generation());assertTrue(store.counts().second>0)
  store.dismissPending();assertFalse(store.active());assertEquals(0,store.counts().second)
  assertTrue(store.recent().isEmpty())
  store.setActive(true);store.add(event,store.generation());assertEquals("discarded id never requeues",0,store.counts().second)
  store.setActive(false)
 }

 @Test fun reviewPageSmoke(){
  store.setActive(false)
  context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(base+"/capture-fixture.html")).setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  val address=device.wait(Until.findObject(By.res("com.android.chrome:id/url_bar")),15000)
  assertNotNull(address);address.click()
  val editor=device.wait(Until.findObject(By.res("com.android.chrome:id/url_bar").focused(true)),10000)
  assertNotNull(editor);editor.text=base+"/activity";device.pressEnter()
  assertTrue("review page rendered",device.wait(Until.hasObject(By.textContains("慢慢找回来")),30000))
  val range=device.wait(Until.findObject(By.text("个人记录").clickable(true)),10000)
  assertNotNull(range);range.click()
  val test=device.wait(Until.findObject(By.text("模拟器测试")),10000)
  assertNotNull(test);test.click()
  device.takeScreenshot(java.io.File(context.filesDir,"review-before-scroll.png"))
  repeat(4){if(!device.hasObject(By.textContains("阅读"))){device.swipe(device.displayWidth/2,device.displayHeight*8/10,device.displayWidth/2,device.displayHeight*3/10,20);Thread.sleep(800)}}
  assertTrue("synthetic timeline content rendered",device.wait(Until.hasObject(By.textContains("阅读")),10000))
  device.takeScreenshot(java.io.File(context.filesDir,"review-smoke.png"))
 }

}
