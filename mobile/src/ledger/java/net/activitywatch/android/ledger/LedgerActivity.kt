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
    private val handler=Handler(Looper.getMainLooper())
    private var selected=mutableSetOf<String>()
    private fun text(value:String,size:Float=16f):TextView=TextView(this).also{it.text=value;it.textSize=size;it.setTextColor(Color.rgb(31,50,43));it.setPadding(0,12,0,12);box.addView(it)}
    private fun button(value:String,action:()->Unit):Button=Button(this).also{it.text=value;it.setOnClickListener{action()};box.addView(it)}
    private fun input(label:String,value:String,secret:Boolean=false):EditText {text(label,13f);return EditText(this).also{it.setSingleLine();it.setText(value);if(secret)it.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;box.addView(it)}}
    override fun onCreate(state:Bundle?){super.onCreate(state);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);store=LedgerStore(this)
        val scroll=ScrollView(this);box=LinearLayout(this);box.orientation=1;box.setPadding(32,42,32,48);box.setBackgroundColor(Color.rgb(245,247,241));scroll.addView(box);setContentView(scroll)
        text("一天",34f);text("记下做过的事，留出自己的空间",16f)
        this.state=text("",16f)
        toggle=button(""){if(store.active()){store.setActive(false);stopService(Intent(this,LedgerService::class.java))}else{if(!SessionUtils.hasUsageStatsPermission(this)){Toast.makeText(this,"请先授予使用情况权限",Toast.LENGTH_LONG).show()}else{store.setActive(true);startForegroundService(Intent(this,LedgerService::class.java))}};refresh()}
        button("1 · 授予应用使用情况权限"){startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))}
        button("2 · 开启内容记录辅助功能（可选）"){startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}
        text("只记录你勾选的应用。内容试用目前支持 Chrome 白名单网页，其他应用仅计时。无痕内容默认跳过，可单独开启；输入中、密码、锁屏、受保护窗口仍跳过。不是完整浏览历史。",14f)
        selected=store.prefs.getString("packages","")!!.split(',').filter{it.isNotBlank()}.toMutableSet()
        val selectedLabel=text("",14f)
        fun selectionLabel(){selectedLabel.text=if(selected.isEmpty())"未选择应用，当前不会记录任何活动" else "已选择 ${selected.size} 个应用"}
        selectionLabel()
        button("选择允许记录的应用"){
            val items=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map{it.activityInfo.packageName to it.loadLabel(packageManager).toString()}.filter{it.first!=packageName}.distinctBy{it.first}.sortedBy{it.second}
            val draft=selected.toMutableSet()
            AlertDialog.Builder(this).setTitle("仅这些应用会被记录").setMultiChoiceItems(items.map{it.second}.toTypedArray(),items.map{it.first in draft}.toBooleanArray()){_,i,checked->if(checked)draft.add(items[i].first)else draft.remove(items[i].first)}.setPositiveButton("选好，稍后保存"){_,_->selected=draft;selectionLabel()}.setNegativeButton("取消",null).show()
        }
        val content=Switch(this).also{it.text="允许采集白名单网页标题 / 可见文字";it.isChecked=store.prefs.getBoolean("content",false);box.addView(it)}
        val incognito=Switch(this).also{it.text="记录无痕页面内容（默认关闭）";it.isChecked=store.prefs.getBoolean("incognito_content",false);it.isEnabled=content.isChecked;box.addView(it)}
        content.setOnCheckedChangeListener{_,enabled->incognito.isEnabled=enabled}
        text("仅控制无痕页面的标题和可见文字，浏览器应用计时仍由应用白名单控制。开启后遵循相同域名白名单，并按同步设置上传到你的服务；浏览器禁止采集的窗口仍会跳过。保存设置并重新开始后生效，不补录过去的内容。关闭不会删除已有记录，可用下方丢弃功能或网页删除。",13f)
        val domains=input("允许的网页域名（英文逗号分隔，精确匹配）",store.prefs.getString("domains","")!!)
        text("不保存原图。只有屏幕上可见的文字，不读取输入框、聊天数据库或后台内容。默认不打开内容采集。",13f)
        text("同步到你的服务",22f)
        val endpoint=input("服务地址",store.prefs.getString("endpoint","")!!)
        val device=input("设备凭据（网页设备页创建；留空保留原值）","",true)
        val site=input("Sites 服务访问凭据（留空保留原值）","",true)
        val sync=Switch(this).also{it.text="允许自动同步（约每分钟）";it.isChecked=store.prefs.getBoolean("sync",false);box.addView(it)}
        button("保存设置并暂停"){
            store.setActive(false);stopService(Intent(this,LedgerService::class.java))
            try {if(device.text.isNotBlank())store.saveSecret("device_token",device.text.toString().trim());if(site.text.isNotBlank())store.saveSecret("site_token",site.text.toString().trim())
                store.prefs.edit().putString("endpoint",endpoint.text.toString().trim()).putString("packages",selected.joinToString(",")).putString("domains",domains.text.toString().trim()).putBoolean("content",content.isChecked).putBoolean("incognito_content",incognito.isChecked).putBoolean("sync",sync.isChecked).commit()
                device.setText("");site.setText("");Toast.makeText(this,"已保存；点开始后生效",Toast.LENGTH_SHORT).show()
            }catch(_:Exception){Toast.makeText(this,"保存失败，请重试",Toast.LENGTH_LONG).show()};refresh()
        }
        button("立即同步已有记录"){if(store.active())startForegroundService(Intent(this,LedgerService::class.java).setAction("sync"))else Toast.makeText(this,"当前已暂停；开始后才能同步",Toast.LENGTH_SHORT).show()}
        text("暂停会停止采集和新的上传请求；已发出的请求无法撤回。断网记录留在本机加密队列，恢复后去重同步。上传确认后本机会清空对应文字，只保留去重标记；查看或删除已上传记录请到网页时间线。",13f)
        button("查看待同步的最近 20 条"){
            val rows=store.recent().joinToString("\n\n"){e->"${e.getString("start")} · ${e.getString("app")}\n"+(if(e.getString("kind")=="content")e.getString("title")+"\n"+e.getString("text") else "应用使用时段")}
            AlertDialog.Builder(this).setTitle("本机待同步预览").setMessage(rows.ifEmpty{"没有待同步内容；已上传的内容请到网页查看"}).setPositiveButton("关闭",null).show()
        }
        button("丢弃本机待同步内容"){
            AlertDialog.Builder(this).setTitle("暂停并丢弃待同步内容？").setMessage("清空本机待同步的文字和区间，仅保留去重标记，不会再次上传。已经发送或上传成功的记录需要在网页删除。").setNegativeButton("取消",null).setPositiveButton("暂停并丢弃"){_,_->store.dismissPending();stopService(Intent(this,LedgerService::class.java));refresh()}.show()
        }
        text("试用版 · Android 14+\n本机加密存储 / 禁用系统备份 / 原图不落盘 / 不自动写入 Toggl\n手机重启后请打开此应用恢复。真机耗电、后台保活和更多应用内容仍待验证。",12f)
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),1)
    }
    private fun refresh(){val n=store.counts();state.text="${if(store.active())"● 记录中" else "○ 已暂停"}  · 已采集 ${n.first} 条  · 待同步 ${n.second} 条\n"+store.prefs.getString("status","数据先留在本机，上传需另行开启")+"\n使用情况权限：${if(SessionUtils.hasUsageStatsPermission(this))"已授予" else "缺失"} · 内容服务：${if(ContentService.connected)"已连接" else "未开启"}";toggle.text=if(store.active())"暂停记录与自动同步" else "开始记录"}
    private val tick=object:Runnable{override fun run(){refresh();handler.postDelayed(this,2000)}}
    override fun onResume(){super.onResume();if(store.active())startForegroundService(Intent(this,LedgerService::class.java));handler.post(tick)}
    override fun onPause(){handler.removeCallbacks(tick);super.onPause()}
}
