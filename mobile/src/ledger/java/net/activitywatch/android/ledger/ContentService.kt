package net.activitywatch.android.ledger

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import net.activitywatch.android.BuildConfig
import java.net.URI

/** Conservative first adapter: Chrome allowlisted pages with explicit incognito opt-in; other apps remain app-time only. */
class ContentService:AccessibilityService(){
    companion object { @Volatile var connected=false }
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var store:LedgerStore
    private var pending=false
    private var lastHash=""
    private var lastAt=0L
    private data class Page(val window:Int,val pkg:String,val title:String,val text:String,val url:String)
    override fun onServiceConnected(){store=LedgerStore(this);connected=true}
    private fun page():Page? {
        if(!::store.isInitialized||!LedgerService.running||!store.active()||!store.prefs.getBoolean("content",false))return null
        if(!getSystemService(PowerManager::class.java).isInteractive||getSystemService(KeyguardManager::class.java).isKeyguardLocked)return null
        val root=rootInActiveWindow?:return null
        try {
            val pkg=root.packageName?.toString()?:return null
            if(!store.allowed(pkg))return null
            if(pkg!="com.android.chrome" && !(BuildConfig.DEBUG&&pkg=="lab.fixture"))return null
            var count=0;var blocked=false;val labels=StringBuilder();val visible=mutableListOf<String>()
            fun read(n:AccessibilityNodeInfo,inWeb:Boolean){
                if(++count>600){blocked=true;return}
                if(n.isPassword || (n.isEditable&&n.isFocused)){blocked=true;return}
                val desc=n.contentDescription?.toString().orEmpty();val id=n.viewIdResourceName.orEmpty()
                labels.append(desc).append(' ').append(id).append(' ')
                if(n.isVisibleToUser){val t=n.text?.toString().orEmpty();labels.append(t).append(' ');if((inWeb||pkg=="lab.fixture")&&t.isNotBlank())visible.add(t)}
                val web=inWeb||n.className?.toString()=="android.webkit.WebView"
                for(i in 0 until n.childCount)n.getChild(i)?.let{try{read(it,web)}finally{it.recycle()}}
            }
            read(root,false)
            val privacy=labels.toString()
            if(blocked||ContentPrivacy.blocks(privacy,store.prefs.getBoolean("incognito_content",false)))return null
            var url=""
            if(pkg=="com.android.chrome"){
                val nodes=root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                val address=nodes.firstOrNull()?.text?.toString().orEmpty();nodes.forEach{it.recycle()}
                if(address.isBlank())return null
                val uri=try{URI(if(address.contains("://"))address else "https://$address")}catch(_:Exception){return null}
                val host=uri.host?.lowercase()?:return null
                val hosts=store.prefs.getString("domains","")!!.split(',').map{it.trim().lowercase()}.filter{it.isNotEmpty()}
                if(host !in hosts || uri.scheme !in listOf("http","https") || uri.userInfo!=null)return null
                val path=uri.rawPath.orEmpty()
                if(Regex("login|signin|auth|checkout|payment|account|settings",RegexOption.IGNORE_CASE).containsMatchIn(path))return null
                url=URI(uri.scheme,uri.authority,path,null,null).toASCIIString()
            } else if(!privacy.contains("PUBLIC_TEST"))return null
            val text=visible.distinct().joinToString("\n").take(6000)
            if(text.isBlank())return null
            return Page(root.windowId,pkg,visible.firstOrNull{it!="PUBLIC_TEST"}.orEmpty().take(500),text,url)
        }finally{root.recycle()}
    }
    private val capture=Runnable {
        if(!pending){
            val page=page()
            if(page!=null){
                val hash=LedgerStore.hash(page.toString());val now=System.currentTimeMillis()
                if(hash!=lastHash||now-lastAt>60000){
                    val generation=store.generation();pending=true
                    // Screenshot is only a secure-window permission gate; pixels are never copied, encoded, retained or uploaded.
                    takeScreenshotOfWindow(page.window,mainExecutor,object:TakeScreenshotCallback{
                        override fun onSuccess(result:ScreenshotResult){
                            try{if(page==page()&&generation==store.generation()){
                                store.add(store.event("content",page.pkg,if(page.pkg=="com.android.chrome")"Chrome" else "测试阅读器",now,now+1,page.title,page.text,page.url),generation)
                                lastHash=hash;lastAt=now
                            }}finally{result.hardwareBuffer.close();pending=false}
                        }
                        override fun onFailure(code:Int){pending=false}
                    })
                }
            }
        }
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?){handler.removeCallbacks(capture);handler.postDelayed(capture,900)}
    override fun onInterrupt(){handler.removeCallbacks(capture)}
    override fun onDestroy(){connected=false;handler.removeCallbacksAndMessages(null);super.onDestroy()}
}
