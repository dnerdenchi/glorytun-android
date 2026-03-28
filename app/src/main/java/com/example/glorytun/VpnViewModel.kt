package com.example.glorytun

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class VpnViewModel : ViewModel() {

    val connectionState = MutableLiveData("Disconnected")

    val wifiKBs = MutableLiveData(0f)
    val simKBs = MutableLiveData(0f)
    val wifiTotalBytes = MutableLiveData(0L)
    val simTotalBytes = MutableLiveData(0L)

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

    fun reset() {
        prevWifiTx = 0L; prevWifiRx = 0L
        prevSimTx = 0L; prevSimRx = 0L
        wifiKBs.value = 0f
        simKBs.value = 0f
        wifiTotalBytes.value = 0L
        simTotalBytes.value = 0L
        maxWifiKBs.value = 0f
        maxSimKBs.value = 0f
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824f)
            bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576f)
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
            else -> "$bytes B"
        }
    }
}
