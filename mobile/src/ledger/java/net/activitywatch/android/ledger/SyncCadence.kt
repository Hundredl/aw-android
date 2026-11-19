package net.activitywatch.android.ledger

object SyncCadence {
    val minutes = listOf(15, 60, 180, 360)
    val labels = listOf("每 15 分钟", "每小时（默认）", "每 3 小时", "每 6 小时")
    fun normalized(value:Int)=if(value in minutes)value else 60
    fun due(now:Long,last:Long,minutes:Int)=now-last>=normalized(minutes)*60_000L
}
