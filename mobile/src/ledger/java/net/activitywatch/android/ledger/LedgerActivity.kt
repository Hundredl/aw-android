package net.activitywatch.android.ledger

import android.app.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.provider.Settings
import android.graphics.Color
import android.view.WindowManager
import android.widget.*
import android.text.InputType
import net.activitywatch.android.utils.SessionUtils

class LedgerActivity:Activity(){
    private lateinit var store:LedgerStore
    private lateinit var box:LinearLayout
    private lateinit var state:TextView
    private lateinit var toggle:Button
    private lateinit var today:TextView
    private var summaryCount=-1
    private var summaryDay=""
    private val handler=Handler(Looper.getMainLooper())
    private var selected=mutableSetOf<String>()
    private var saveAndStart:()->Unit = {}
    private fun hasSavedApps()=store.prefs.getString("packages","").orEmpty().split(',').any{it.isNotBlank()&&it!=packageName}
    private fun startRecording(){
        if(!hasSavedApps()){Toast.makeText(this,"先选择要记录的应用",Toast.LENGTH_LONG).show();return}
        if(!SessionUtils.hasUsageStatsPermission(this)){Toast.makeText(this,"请先授予使用情况权限",Toast.LENGTH_LONG).show();return}
        store.setActive(true);startForegroundService(Intent(this,LedgerService::class.java))
    }
    private fun text(value:String,size:Float=16f):TextView=TextView(this).also{it.text=value;it.textSize=size;it.setTextColor(Color.rgb(31,50,43));it.setPadding(0,12,0,12);box.addView(it)}
    private fun button(value:String,action:()->Unit):Button=Button(this).also{it.text=value;it.setOnClickListener{action()};box.addView(it)}
    private fun input(label:String,value:String,secret:Boolean=false):EditText {text(label,13f);return EditText(this).also{it.setSingleLine();it.setText(value);if(secret)it.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;box.addView(it)}}
    override fun onCreate(state:Bundle?){super.onCreate(state);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);store=LedgerStore(this)
        val scroll=ScrollView(this);box=LinearLayout(this);box.orientation=1;box.setPadding(32,42,32,48);box.setBackgroundColor(Color.rgb(245,247,241));scroll.addView(box);setContentView(scroll)
        text("一天",34f);text("记下做过的事，留出自己的空间",16f)
        this.state=text("",16f)
        toggle=button(""){if(store.active()){store.setActive(false);stopService(Intent(this,LedgerService::class.java))}else{saveAndStart()};refresh()}
        today=text("",16f)
        button("查看本地历史与统计"){showLocalDay(java.time.LocalDate.now())}
        button("提取截图文字"){startActivity(Intent(this,OcrPreviewActivity::class.java))}
        text("记录和统计都在手机完成，无需联网。同步只作为可选备份，成功后仍保留本地加密记录。",14f)
        button("1 · 授予应用使用情况权限"){startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))}
        button("2 · 开启内容记录辅助功能（可选）"){startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}
        text("只记录你勾选的应用。内容试用目前支持 Chrome 白名单网页，飞书截图识别可独立开启。无痕内容默认跳过，可单独开启；输入中、密码、锁屏、受保护窗口仍跳过。不是完整浏览历史。",14f)
        selected=store.prefs.getString("packages","")!!.split(',').filter{it.isNotBlank()}.toMutableSet()
        val selectedLabel=text("",14f)
        fun selectionLabel(){selectedLabel.text=if(selected.isEmpty())"未选择应用，当前不会记录任何活动" else "已选择 ${selected.size} 个应用"}
        selectionLabel()
        button("选择允许记录的应用"){
            val items=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map{it.activityInfo.packageName to it.loadLabel(packageManager).toString()}.filter{it.first!=packageName}.distinctBy{it.first}.sortedBy{it.second}
            val draft=selected.toMutableSet()
            val dialog=AlertDialog.Builder(this).setTitle("允许记录的应用").setMultiChoiceItems(items.map{it.second}.toTypedArray(),items.map{it.first in draft}.toBooleanArray(),null).setPositiveButton("确定"){_,_->
                val wasActive=store.active();store.setActive(false)
                if(store.prefs.edit().putString("packages",draft.joinToString(",")).commit()){
                    selected=draft;selectionLabel()
                    if(wasActive&&hasSavedApps())startRecording()else stopService(Intent(this,LedgerService::class.java))
                    Toast.makeText(this,if(selected.isEmpty())"已清空应用选择，记录已暂停" else if(wasActive)"应用已保存，继续记录" else "应用已保存，点开始记录即可",Toast.LENGTH_SHORT).show()
                }else Toast.makeText(this,"保存失败，请重试",Toast.LENGTH_LONG).show()
                refresh()
            }.setNegativeButton("取消",null).setNeutralButton("全选",null).create()
            dialog.setOnShowListener {
                val bulk=dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                fun refreshBulk(){bulk.text=if(items.isNotEmpty()&&items.all{it.first in draft})"全不选" else "全选";bulk.isEnabled=items.isNotEmpty()}
                dialog.listView.setOnItemClickListener{_,_,i,_->if(dialog.listView.isItemChecked(i))draft.add(items[i].first)else draft.remove(items[i].first);refreshBulk()}
                bulk.setOnClickListener {
                    val selectAll=!items.all{it.first in draft}
                    items.forEachIndexed { i,item -> if(selectAll)draft.add(item.first)else draft.remove(item.first);dialog.listView.setItemChecked(i,selectAll) }
                    refreshBulk()
                }
                refreshBulk()
            }
            dialog.show()
        }
        val content=Switch(this).also{it.text="允许采集白名单网页标题 / 可见文字";it.isChecked=store.prefs.getBoolean("content",false);box.addView(it)}
        val incognito=Switch(this).also{it.text="记录无痕页面内容（默认关闭）";it.isChecked=store.prefs.getBoolean("incognito_content",false);it.isEnabled=content.isChecked;box.addView(it)}
        content.setOnCheckedChangeListener{_,enabled->incognito.isEnabled=enabled}
        text("仅控制无痕页面的标题和可见文字，浏览器应用计时仍由应用白名单控制。开启后遵循相同域名白名单，并按同步设置上传到你的服务；浏览器禁止采集的窗口仍会跳过。点“保存并开始记录”后生效，不补录过去的内容。关闭不会删除已有记录，可用下方丢弃功能或网页删除。",13f)
        val domains=input("允许的网页域名（英文逗号分隔，精确匹配）",store.prefs.getString("domains","")!!)
        text("不保存原图。只有屏幕上可见的文字，不读取输入框、聊天数据库或后台内容。默认不打开内容采集。",13f)
        text("飞书页面识别",22f)
        val autoOcr=Switch(this).apply {
            text="自动识别飞书页面（仅本机）"
            isChecked=store.prefs.getBoolean("automatic_ocr",false)
            setOnCheckedChangeListener { _, enabled ->
                if(!store.setAutomaticOcr(enabled)){
                    Toast.makeText(this@LedgerActivity,"设置保存失败，请重试",Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            box.addView(this)
        }
        text("开启辅助功能、允许记录飞书并开始记录后，停在页面上会自动识别可见文字。文字可能包含聊天内容，保存在本机历史。",14f)
        button("清除本机截图文字"){
            AlertDialog.Builder(this).setTitle("清除本机自动识别的截图文字？")
                .setMessage("同时关闭飞书自动识别。应用使用时段仍可查看。")
                .setNegativeButton("取消",null)
                .setPositiveButton("关闭并清除"){_,_->
                    autoOcr.isChecked=false
                    store.clearLocalOcr()
                    summaryCount=-1
                    refresh()
                }.show()
        }
        val cloudSettings=LinearLayout(this).also{it.orientation=1;it.visibility=android.view.View.GONE}
        button("云端同步设置（可选）"){cloudSettings.visibility=if(cloudSettings.visibility==android.view.View.VISIBLE)android.view.View.GONE else android.view.View.VISIBLE}
        val root=box;box.addView(cloudSettings);box=cloudSettings
        text("同步到你的服务",22f)
        val endpoint=input("服务地址",store.prefs.getString("endpoint","")!!)
        val device=input("设备凭据（网页设备页创建；留空保留原值）","",true)
        val site=input("Sites 服务访问凭据（留空保留原值）","",true)
        val sync=Switch(this).also{it.text="允许自动同步";it.isChecked=store.prefs.getBoolean("sync",false);box.addView(it)}
        text("自动同步频率",13f)
        val frequency=Spinner(this).also {
            it.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,SyncCadence.labels)
            it.setSelection(SyncCadence.minutes.indexOf(SyncCadence.normalized(store.prefs.getInt("sync_interval_minutes",60))))
            box.addView(it)
        }
        text("默认约每小时批量上传；后台休眠或断网可能延后。也可以点立即同步。",13f)
        box=root
        saveAndStart = {
            store.setActive(false);stopService(Intent(this,LedgerService::class.java))
            try {if(device.text.isNotBlank())store.saveSecret("device_token",device.text.toString().trim());if(site.text.isNotBlank())store.saveSecret("site_token",site.text.toString().trim())
                store.prefs.edit().putString("endpoint",endpoint.text.toString().trim()).putString("packages",selected.joinToString(",")).putString("domains",domains.text.toString().trim()).putBoolean("content",content.isChecked).putBoolean("incognito_content",incognito.isChecked).putBoolean("sync",sync.isChecked).putInt("sync_interval_minutes",SyncCadence.minutes[frequency.selectedItemPosition]).commit()
                device.setText("");site.setText("")
                if(sync.isChecked&&(endpoint.text.isBlank()||store.secret("device_token").isBlank()||store.secret("site_token").isBlank())){
                    sync.isChecked=false;store.prefs.edit().putBoolean("sync",false).commit()
                    Toast.makeText(this,"云端配对尚未完成，先在本机记录",Toast.LENGTH_LONG).show()
                }
                startRecording()
            }catch(_:Exception){Toast.makeText(this,"保存失败，请重试",Toast.LENGTH_LONG).show()};refresh()
        }
        button("保存并开始记录"){saveAndStart()}
        button("立即同步已有记录"){if(store.active())startForegroundService(Intent(this,LedgerService::class.java).setAction("sync"))else Toast.makeText(this,"当前已暂停；开始后才能同步",Toast.LENGTH_SHORT).show()}
        text("暂停会停止采集和新的上传请求；已发出的请求无法撤回。断网记录留在本机加密队列，恢复后去重同步。上传成功后本地加密记录仍保留，可离线回顾；云端记录可到网页管理。",13f)
        button("查看最近 20 条本地记录"){
            val rows=store.recent().joinToString("\n\n"){e->"${e.getString("start")} · ${e.getString("app")}\n"+(if(e.getString("kind")=="content")e.getString("title")+"\n"+e.getString("text") else "应用使用时段")}
            AlertDialog.Builder(this).setTitle("本地记录预览").setMessage(rows.ifEmpty{"暂无本地记录"}).setPositiveButton("关闭",null).show()
        }
        button("丢弃本机待同步内容"){
            AlertDialog.Builder(this).setTitle("暂停并丢弃待同步内容？").setMessage("清空本机待同步的文字和区间，仅保留去重标记，不会再次上传。已经发送或上传成功的记录需要在网页删除。").setNegativeButton("取消",null).setPositiveButton("暂停并丢弃"){_,_->store.dismissPending();stopService(Intent(this,LedgerService::class.java));refresh()}.show()
        }
        text("试用版 · Android 14+\n本机加密存储 / 禁用系统备份 / 原图不落盘 / 不自动写入 Toggl\n手机重启后请打开此应用恢复。真机耗电、后台保活和更多应用内容仍待验证。",12f)
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),1)
    }
    private fun refresh(){
        val n=store.counts()
        if(::today.isInitialized&&(summaryCount!=n.first||summaryDay!=java.time.LocalDate.now().toString())){val d=localDay(java.time.LocalDate.now());summaryCount=n.first;summaryDay=java.time.LocalDate.now().toString();today.text="今天 · 应用累计 ${duration(d.first.values.sum())} · ${d.first.size} 个应用\n本地记录 ${d.second.size} 条（当前时段结束后计入）"}
        val allowed=hasSavedApps();val permission=SessionUtils.hasUsageStatsPermission(this)
        val headline=when{!allowed->"○ 未开始：先选择应用";!permission->"○ 未开始：需要使用情况权限";store.active()&&LedgerService.running->"● 正在本机记录";store.active()->"◌ 正在启动";else->"○ 已暂停"}
        val paired=store.prefs.getString("endpoint","").orEmpty().isNotBlank()&&store.prefs.contains("device_token")&&store.prefs.contains("site_token")
        val cloud=if(!paired)"云端未配置 · 记录只留在本机" else if(store.prefs.getBoolean("sync",false))"自动同步 · ${SyncCadence.labels[SyncCadence.minutes.indexOf(SyncCadence.normalized(store.prefs.getInt("sync_interval_minutes",60)))]}" else "自动同步已关闭"
        val ocrState=if(!store.prefs.getBoolean("automatic_ocr",false))"" else if(ContentService.connected&&store.active()&&store.allowed("com.ss.android.lark"))"\n飞书自动识别 · 已开启" else "\n飞书自动识别 · 等待飞书授权、辅助功能和记录开启"
        state.text="$headline  · 本机已采集 ${n.first} 条\n$cloud${if(paired)" · 待同步 ${n.second} 条" else ""}\n"+store.prefs.getString("status","")+"\n使用情况权限：${if(permission)"已授予" else "缺失"} · 内容服务：${if(ContentService.connected)"已连接" else "未开启"}$ocrState"
        toggle.text=if(store.active())"暂停记录与自动同步" else "开始记录"
    }
    private fun duration(ms:Long):String {val m=ms/60000;return if(m>=60)"${m/60} 小时 ${m%60} 分钟" else if(m>0)"$m 分钟" else "${ms/1000} 秒"}
    private fun localDay(day:java.time.LocalDate):Pair<Map<String,Long>,List<org.json.JSONObject>> {
        val zone=java.time.ZoneId.systemDefault();val from=day.atStartOfDay(zone).toInstant().toEpochMilli();val to=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val totals=mutableMapOf<String,Long>();val rows=mutableListOf<org.json.JSONObject>()
        for(e in store.localEvents())try{
            val a=java.time.Instant.parse(e.getString("start")).toEpochMilli();val b=java.time.Instant.parse(e.getString("stop")).toEpochMilli()
            if(a<to&&b>from){rows.add(e);if(e.optString("kind")=="app"){val name=e.optString("app",e.optString("package"));totals[name]=(totals[name]?:0)+maxOf(0,minOf(b,to)-maxOf(a,from))}}
        }catch(_:Exception){}
        return totals to rows
    }
    private fun showLocalDay(day:java.time.LocalDate){
        val data=localDay(day);val format=java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
        val report=buildString {
            append("应用累计使用 ${duration(data.first.values.sum())}\n（按应用时段累计，重叠使用可能重复计入）\n\n")
            data.first.entries.sortedByDescending{it.value}.forEach{append("${it.key} · ${duration(it.value)}\n")}
            append("\n当天记录\n")
            data.second.sortedBy{it.optString("start")}.takeLast(300).forEach {e->
                append("\n${format.format(java.time.Instant.parse(e.getString("start")))}–${format.format(java.time.Instant.parse(e.getString("stop")))}  ${e.optString("app")}\n")
                if(e.optString("kind")=="content")append("${e.optString("title")}\n${e.optString("text")}\n")
            }
            if(data.second.isEmpty())append("还没有记录。选好应用并开始后，完成的应用使用时段会出现在这里。")
            if(data.second.size>300)append("\n显示最近300条；统计包含全天数据。")
        }
        AlertDialog.Builder(this).setTitle("$day · 本地记录").setMessage(report).setPositiveButton("关闭",null).setNegativeButton("前一天"){_,_->showLocalDay(day.minusDays(1))}.setNeutralButton("选择日期"){_,_->DatePickerDialog(this,{_,y,m,d->showLocalDay(java.time.LocalDate.of(y,m+1,d))},day.year,day.monthValue-1,day.dayOfMonth).show()}.show()
    }
    private val tick=object:Runnable{override fun run(){refresh();handler.postDelayed(this,2000)}}
    override fun onResume(){super.onResume();if(store.active()){if(hasSavedApps()&&SessionUtils.hasUsageStatsPermission(this))startForegroundService(Intent(this,LedgerService::class.java))else{store.setActive(false);stopService(Intent(this,LedgerService::class.java))}};handler.post(tick)}
    override fun onPause(){handler.removeCallbacks(tick);super.onPause()}
}
