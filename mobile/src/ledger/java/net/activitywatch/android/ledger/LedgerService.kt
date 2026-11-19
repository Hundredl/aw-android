package net.activitywatch.android.ledger

import android.app.*
import android.content.Intent
import android.os.*
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import net.activitywatch.android.parser.SessionParser
import net.activitywatch.android.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LedgerService:Service(){
    companion object { @Volatile var running=false }
    private lateinit var store:LedgerStore
    private val worker=Executors.newSingleThreadScheduledExecutor()
    private var lastSync=0L
    override fun onBind(i:Intent?)=null
    override fun onCreate(){super.onCreate();store=LedgerStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("ledger","活动记录",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,LedgerActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,1,Intent(this,LedgerService::class.java).setAction("pause"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(42,Notification.Builder(this,"ledger").setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("一天 · 正在记录白名单应用").setContentText("点此查看；暂停立即停止采集和自动上传").setContentIntent(open).addAction(Notification.Action.Builder(null,"暂停",pause).build()).build())
        running=true
        worker.scheduleWithFixedDelay({try{collect();if(store.prefs.getBoolean("sync",false)&&System.currentTimeMillis()-lastSync>60000){sync();lastSync=System.currentTimeMillis()}}catch(_:Exception){store.status("采集或同步暂不可用；本地记录已保留")}},2,10,TimeUnit.SECONDS)
    }
    override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int {
        if(i?.action=="pause"){store.setActive(false);stopSelf();return START_NOT_STICKY}
        if(!store.active()||store.prefs.getString("packages","").orEmpty().split(',').none{it.isNotBlank()&&it!=packageName}){store.setActive(false);stopSelf();return START_NOT_STICKY}
        if(i?.action=="sync")worker.execute{try{collect();sync()}catch(_:Exception){store.status("同步失败；本地队列保留")}}
        return START_STICKY
    }
    override fun onDestroy(){running=false;worker.shutdownNow();super.onDestroy()}
    private fun collect(){
        val generation=store.generation();if(!store.active())return
        val since=store.prefs.getLong("since",System.currentTimeMillis());val now=System.currentTimeMillis()
        // Reuse ActivityWatch's completed-session parser. Never backfill before the current consent window.
        val sessions=SessionParser(this).parseUsageEventsForPeriod(maxOf(since,store.prefs.getLong("cursor",since),now-86400000),now)
        for(s in sessions)if(s.startTime>=since && store.allowed(s.packageName))store.add(store.event("app",s.packageName,s.appName,s.startTime,s.endTime),generation)
        sessions.maxOfOrNull{it.endTime}?.let{store.advanceCursor(maxOf(since,it-1),generation)}
    }
    private fun sync(){
        val g=store.generation();if(!store.active())return
        val endpoint=store.prefs.getString("endpoint","")!!.trim().trimEnd('/')
        val uri=URI(endpoint)
        require(uri.scheme=="https" || (BuildConfig.DEBUG && uri.scheme=="http" && uri.host in listOf("127.0.0.1","localhost")))
        require(uri.userInfo==null&&uri.query==null&&uri.fragment==null&&uri.path in listOf("","/"))
        val token=store.secret("device_token");require(token.isNotBlank())
        val batch=store.pending();if(batch.length()==0){store.status("已同步，没有待上传记录");return}
        val con=URI("$endpoint/api/capture").toURL().openConnection() as HttpURLConnection
        try {
            con.instanceFollowRedirects=false;con.connectTimeout=10000;con.readTimeout=10000;con.requestMethod="POST";con.doOutput=true
            con.setRequestProperty("Content-Type","application/json");con.setRequestProperty("Authorization","Bearer $token")
            val service=store.secret("site_token");if(service.isNotBlank())con.setRequestProperty("OAI-Sites-Authorization","Bearer $service")
            // Pause prevents queued uploads. A request already sent cannot be recalled.
            synchronized(LedgerStore.lock){if(!store.active()||g!=store.generation())return}
            con.outputStream.use{it.write(JSONObject().put("events",batch).toString().toByteArray())}
            when(con.responseCode){
                200->{val response=con.inputStream.bufferedReader().use{it.readText().take(100000)};store.acknowledge(JSONObject(response).getJSONArray("accepted"));store.status("同步成功 · 本批 ${batch.length()} 条")}
                401,403->{store.prefs.edit().putBoolean("sync",false).apply();store.status("凭据失效或设备已撤销，自动同步已关闭")}
                else->store.status("服务暂不可用（${con.responseCode}）；稍后自动重试")
            }
        }finally{con.disconnect()}
    }
}
