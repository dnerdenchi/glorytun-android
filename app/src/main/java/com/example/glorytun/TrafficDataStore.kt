package com.example.glorytun

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 1時間単位の通信量集計データを内部ストレージのJSONファイルに永続化するクラス。
 * 最大1年分（8784時間）を保持する。
 */
class TrafficDataStore(context: Context) {

    private val file = File(context.filesDir, "traffic_history.json")
    private val MAX_POINTS = 8784 // 365日 × 24時間

    @Synchronized
    fun load(): List<TrafficPoint> = loadInternal()

    /** 1時間分の集計ポイントを追加し、古いデータを整理して保存する */
    @Synchronized
    fun appendPoint(point: TrafficPoint) {
        val existing = loadInternal().toMutableList()
        // 同じ時間のエントリがすでにあれば上書き（切断→再接続で同じ時間帯に複数保存されるケース）
        val sameHour = existing.indexOfFirst {
            it.timestamp / 3_600_000L == point.timestamp / 3_600_000L
        }
        if (sameHour >= 0) {
            existing[sameHour] = point
        } else {
            existing.add(point)
        }
        val pruned = existing.sortedBy { it.timestamp }.takeLast(MAX_POINTS)
        saveInternal(pruned)
    }

    private fun loadInternal(): List<TrafficPoint> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TrafficPoint(
                    timestamp = obj.getLong("t"),
                    wifiKBs  = obj.getDouble("w").toFloat(),
                    simKBs   = obj.getDouble("s").toFloat()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveInternal(points: List<TrafficPoint>) {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("t", p.timestamp)
                put("w", p.wifiKBs.toDouble())
                put("s", p.simKBs.toDouble())
            })
        }
        file.writeText(arr.toString())
    }
}
