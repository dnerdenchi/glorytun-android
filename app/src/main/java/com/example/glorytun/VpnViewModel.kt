package com.example.glorytun

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.util.Calendar

/** 時刻付き通信量データポイント */
data class TrafficPoint(val timestamp: Long, val wifiKBs: Float, val simKBs: Float)

/** 応答確認キャッシュエントリ */
data class ServerCheckEntry(
    val checkedAt: Long,       // System.currentTimeMillis()
    val reachable: Boolean,
    val detail: String,
    val rttMs: Long
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    val trafficDataStore = TrafficDataStore(application)

    /**
     * 永続化された時間単位の通信量履歴（アプリ起動時にファイルからロード）。
     * GlorytunVpnService が1時間ごとに集計・保存したデータ。
     */
    val historicalPoints = mutableListOf<TrafficPoint>()

    /**
     * 現在のセッションの通信量履歴（最大24時間分 = 86400点）。
     * VPN接続中のみ追加される。
     */
    val trafficHistory = mutableListOf<TrafficPoint>()

    /** リアルタイムグラフ用データ（最大60点）- タブを切り替えても保持される */
    val realtimeWifiRates = ArrayDeque<Float>()
    val realtimeSimRates = ArrayDeque<Float>()
    val realtimeUpdated = MutableLiveData(0L)

    val connectionState = MutableLiveData("Disconnected")

    val wifiKBs = MutableLiveData(0f)
    val simKBs = MutableLiveData(0f)
    val wifiTotalBytes = MutableLiveData(0L)
    val simTotalBytes = MutableLiveData(0L)

    /** 当日（0時〜現在）の通信量（バイト） */
    val wifiDailyBytes = MutableLiveData(0L)
    val simDailyBytes = MutableLiveData(0L)

    /** 通信制限中フラグ */
    val wifiThrottled = MutableLiveData(false)
    val simThrottled = MutableLiveData(false)

    val maxWifiKBs = MutableLiveData(0f)
    val maxSimKBs = MutableLiveData(0f)

    // 前回値（スループット計算用）
    var prevWifiTx = 0L
    var prevWifiRx = 0L
    var prevSimTx = 0L
    var prevSimRx = 0L

    // サーバー設定
    val serverIp = MutableLiveData("")
    val serverPort = MutableLiveData("5000")

    /** profileId → 最後の応答確認結果（ページ遷移を跨いで保持） */
    val serverCheckCache = mutableMapOf<String, ServerCheckEntry>()

    init {
        // アプリ起動時に永続化済みの時間集計データをロード
        historicalPoints.addAll(trafficDataStore.load())
        // historicalPoints から当日分の通信量を計算して初期値を設定
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var dWifi = 0L
        var dSim = 0L
        for (p in historicalPoints) {
            if (p.timestamp >= todayStart) {
                dWifi += (p.wifiKBs * 3600 * 1024).toLong()
                dSim  += (p.simKBs  * 3600 * 1024).toLong()
            }
        }
        wifiDailyBytes.value = dWifi
        simDailyBytes.value  = dSim
    }

    /** サービスが1時間分の集計を保存したときに呼ばれる（重複防止済み） */
    fun addHistoricalPoint(point: TrafficPoint) {
        val existingIdx = historicalPoints.indexOfFirst {
            it.timestamp / 3_600_000L == point.timestamp / 3_600_000L
        }
        if (existingIdx >= 0) {
            historicalPoints[existingIdx] = point
        } else {
            historicalPoints.add(point)
        }
    }

    fun updateTraffic(
        wifiActive: Boolean,
        simActive: Boolean,
        wifiTx: Long,
        wifiRx: Long,
        simTx: Long,
        simRx: Long
    ) {
        val newWifiKBs = if (wifiActive && prevWifiTx > 0) {
            ((wifiTx - prevWifiTx) + (wifiRx - prevWifiRx)).coerceAtLeast(0L) / 1024f
        } else 0f

        val newSimKBs = if (simActive && prevSimTx > 0) {
            ((simTx - prevSimTx) + (simRx - prevSimRx)).coerceAtLeast(0L) / 1024f
        } else 0f

        wifiKBs.value = newWifiKBs
        simKBs.value = newSimKBs

        if (newWifiKBs > (maxWifiKBs.value ?: 0f)) maxWifiKBs.value = newWifiKBs
        if (newSimKBs > (maxSimKBs.value ?: 0f)) maxSimKBs.value = newSimKBs

        // 現セッションの履歴に追加（最大86400点 = 24時間分）
        trafficHistory.add(TrafficPoint(System.currentTimeMillis(), newWifiKBs, newSimKBs))
        while (trafficHistory.size > 86400) trafficHistory.removeAt(0)

        // リアルタイムグラフ用データを更新
        addRealtimePoint(newWifiKBs, newSimKBs)

        if (wifiActive) {
            wifiTotalBytes.value = wifiTx + wifiRx
            prevWifiTx = wifiTx
            prevWifiRx = wifiRx
        } else {
            prevWifiTx = 0L
            prevWifiRx = 0L
        }

        if (simActive) {
            simTotalBytes.value = simTx + simRx
            prevSimTx = simTx
            prevSimRx = simRx
        } else {
            prevSimTx = 0L
            prevSimRx = 0L
        }
    }

    fun addRealtimePoint(wifi: Float, sim: Float) {
        if (realtimeWifiRates.size >= 60) realtimeWifiRates.removeFirst()
        if (realtimeSimRates.size >= 60) realtimeSimRates.removeFirst()
        realtimeWifiRates.addLast(wifi.coerceAtLeast(0f))
        realtimeSimRates.addLast(sim.coerceAtLeast(0f))
        realtimeUpdated.value = System.currentTimeMillis()
    }

    fun resetRealtimeData() {
        realtimeWifiRates.clear()
        realtimeSimRates.clear()
        realtimeUpdated.postValue(0L)
    }

    /** VPN接続中にブロードキャストから受け取った当日通信量・制限状態を更新する */
    fun updateDailyTraffic(wifiKB: Double, simKB: Double, wThrottled: Boolean, sThrottled: Boolean) {
        wifiDailyBytes.value = (wifiKB * 1024).toLong()
        simDailyBytes.value  = (simKB  * 1024).toLong()
        wifiThrottled.value  = wThrottled
        simThrottled.value   = sThrottled
    }

    fun reset() {
        prevWifiTx = 0L; prevWifiRx = 0L
        prevSimTx = 0L; prevSimRx = 0L
        wifiKBs.value = 0f
        simKBs.value = 0f
        wifiThrottled.value = false
        simThrottled.value  = false
    }

    /**
     * KB/s → bps 変換して適切な単位で表示する。
     * 100 kbps 超は Mbps 表示。
     */
    fun formatBps(kbs: Float): String {
        val bps = kbs * 8192f   // KB/s × 8 × 1024 = bps
        return when {
            bps >= 100_000f -> "%.2f Mbps".format(bps / 1_000_000f)
            bps >= 1000f    -> "%.1f kbps".format(bps / 1000f)
            else            -> "%.0f bps".format(bps)
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824f)
            bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576f)
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
            else -> "%.2f GB".format(bytes / 1_073_741_824f)
        }
    }
}
