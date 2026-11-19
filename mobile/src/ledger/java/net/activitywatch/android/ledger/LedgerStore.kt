package net.activitywatch.android.ledger

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.time.Instant

/** All changes to capture state and inserts share this lock, including async callbacks. */
class LedgerStore(val context: Context) {
    val prefs = context.getSharedPreferences("ledger", Context.MODE_PRIVATE)
    companion object { val lock = Any(); fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)} }
    private val helper=object:SQLiteOpenHelper(context,"private-ledger.db",null,1){
        override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,payload TEXT NOT NULL,synced INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL)")}
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){}
    }
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        return (ks.getKey("activity-ledger-v1",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("activity-ledger-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
    }
    private fun seal(s:String):String {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(c.iv+c.doFinal(s.toByteArray()),Base64.NO_WRAP)}
    private fun open(s:String):String {val bytes=Base64.decode(s,Base64.NO_WRAP);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));return String(c.doFinal(bytes.copyOfRange(12,bytes.size)))}
    fun secret(name:String)=prefs.getString(name,null)?.let{open(it)} ?: ""
    fun saveSecret(name:String,value:String){prefs.edit().putString(name,seal(value)).commit()}
    fun active()=prefs.getBoolean("active",false)
    fun generation()=prefs.getLong("generation",0)
    fun setActive(value:Boolean)=synchronized(lock){prefs.edit().putBoolean("active",value).putLong("since",System.currentTimeMillis()).putLong("cursor",System.currentTimeMillis()).putLong("generation",generation()+1).commit()}
    fun allowed(pkg:String)=prefs.getString("packages","")!!.split(',').map{it.trim()}.contains(pkg) && pkg!=context.packageName
    fun status(s:String){prefs.edit().putString("status",s).apply()}
    fun add(e:JSONObject,expected:Long)=synchronized(lock){if(!active()||expected!=generation()||!allowed(e.getString("package")))return@synchronized;if(helper.readableDatabase.rawQuery("SELECT 1 FROM events WHERE id=?",arrayOf(e.getString("id"))).use{it.moveToFirst()})return@synchronized;helper.writableDatabase.insertWithOnConflict("events",null,ContentValues().apply{put("id",e.getString("id"));put("payload",seal(e.toString()));put("created",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_IGNORE)}
    fun advanceCursor(timestamp:Long,expected:Long)=synchronized(lock){if(active()&&expected==generation())prefs.edit().putLong("cursor",timestamp).commit()}
    fun pending(limit:Int=50):JSONArray=synchronized(lock){val a=JSONArray();helper.readableDatabase.rawQuery("SELECT payload FROM events WHERE synced=0 ORDER BY created LIMIT ?",arrayOf(limit.toString())).use{while(it.moveToNext())a.put(JSONObject(open(it.getString(0))))};a}
    fun acknowledge(ids:JSONArray)=synchronized(lock){helper.writableDatabase.beginTransaction();try{for(i in 0 until ids.length())helper.writableDatabase.execSQL("UPDATE events SET synced=1 WHERE id=?",arrayOf(ids.getString(i)));helper.writableDatabase.setTransactionSuccessful()}finally{helper.writableDatabase.endTransaction()}}
    fun dismissPending()=synchronized(lock){setActive(false);helper.writableDatabase.execSQL("UPDATE events SET synced=1,payload='' WHERE synced=0")}
    fun counts():Pair<Int,Int> = synchronized(lock){helper.readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN synced=0 THEN 1 ELSE 0 END),0) FROM events",null).use{it.moveToFirst();it.getInt(0) to it.getInt(1)}}
    fun localEvents():List<JSONObject> = synchronized(lock){
        val rows=mutableListOf<JSONObject>()
        helper.readableDatabase.rawQuery("SELECT payload FROM events WHERE payload<>'' ORDER BY created DESC",null).use{while(it.moveToNext())try{rows.add(JSONObject(open(it.getString(0))))}catch(_:Exception){}}
        rows
    }
    fun recent():List<JSONObject> = synchronized(lock){val rows=mutableListOf<JSONObject>();helper.readableDatabase.rawQuery("SELECT payload FROM events WHERE payload<>'' ORDER BY created DESC LIMIT 20",null).use{while(it.moveToNext())rows.add(JSONObject(open(it.getString(0))))};rows}
    fun event(kind:String,pkg:String,app:String,start:Long,stop:Long,title:String="",text:String="",url:String=""):JSONObject = JSONObject().put("id",hash("$kind|$pkg|$start|$stop|$title|$text|$url")).put("kind",kind).put("package",pkg).put("app",app.take(200)).put("start",Instant.ofEpochMilli(start).toString()).put("stop",Instant.ofEpochMilli(stop).toString()).put("title",title.take(500)).put("text",text.take(6000)).put("url",url.take(2000))
}
