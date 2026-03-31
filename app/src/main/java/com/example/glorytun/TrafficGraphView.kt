package com.example.glorytun

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * WiFi と SIM の通信スループット (KB/s) を折れ線グラフまたは棒グラフで表示するカスタムView。
 * - 青: WiFi (TX+RX合計)
 * - オレンジ: SIM (TX+RX合計)
 * - barMode=true のとき棒グラフ（WiFi下段・SIM上段の積み上げ棒）を表示
 */
class TrafficGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_POINTS = 60
    }

    /** true のとき棒グラフを描画する */
    var barMode = false
        set(value) { field = value; invalidate() }

    /** バーモード時のX軸ラベル（各ビンに対応、空文字は非表示） */
    var xAxisLabels: List<String> = emptyList()
        set(value) { field = value; invalidate() }

    private val wifiRates = ArrayDeque<Float>()
    private val simRates  = ArrayDeque<Float>()

    private val wifiLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#2196F3")
        strokeWidth = 4f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val simLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#FF9800")
        strokeWidth = 4f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val wifiBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.FILL
    }

    private val simBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#414755")
        strokeWidth = 1f
        style       = Paint.Style.STROKE
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#8b90a0")
        strokeWidth = 1.5f
        style       = Paint.Style.STROKE
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1c2026")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.parseColor("#8b90a0")
        textSize = 26f
    }

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.parseColor("#8b90a0")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#8b90a0")
        textSize  = 32f
        textAlign = Paint.Align.CENTER
    }

    fun addDataPoint(wifiKBs: Float, simKBs: Float) {
        if (wifiRates.size >= MAX_POINTS) wifiRates.removeFirst()
        if (simRates.size  >= MAX_POINTS) simRates.removeFirst()
        wifiRates.addLast(wifiKBs.coerceAtLeast(0f))
        simRates.addLast(simKBs.coerceAtLeast(0f))
        invalidate()
    }

    fun reset() {
        wifiRates.clear()
        simRates.clear()
        invalidate()
    }

    fun setData(wifiData: List<Float>, simData: List<Float>) {
        wifiRates.clear()
        simRates.clear()
        wifiData.forEach { wifiRates.addLast(it.coerceAtLeast(0f)) }
        simData.forEach { simRates.addLast(it.coerceAtLeast(0f)) }
        invalidate()
    }

    var xLabelLeft = "60s前"
        set(value) { field = value; invalidate() }

    var xLabelRight = "現在"
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val padL = 72f
        val padR = 8f
        val padT = 8f
        val hasXLabels = barMode && xAxisLabels.isNotEmpty()
        val padB = if (hasXLabels) 48f else 28f

        val graphW = w - padL - padR
        val graphH = h - padT - padB

        val plotPadT = 6f
        val plotPadB = 10f
        val plotH    = graphH - plotPadT - plotPadB

        // 背景
        canvas.drawRect(padL, padT, w - padR, h - padB, bgPaint)

        // Y軸スケール計算
        // バーモード（積み上げ棒）の場合は各ビンの合計値の最大を使う
        val rawMax = if (barMode && wifiRates.size == simRates.size && wifiRates.isNotEmpty()) {
            (wifiRates.indices).maxOf { i -> wifiRates[i] + simRates[i] }
        } else {
            (wifiRates.toList() + simRates.toList()).maxOrNull() ?: 0f
        }

        // グラフ描画上限 (plotMax) を決定
        // バーモード: ステップの倍数に切り上げた値を上限にする
        // リアルタイム: 最低1Mbps(125 KB/s)、超えたら1.25倍
        val plotMax: Float
        val gridValues: List<Float>
        if (barMode) {
            val gb = 1_073_741_824f
            val stepCandidates = listOf(
                0.5f * gb, 1f * gb, 2f * gb, 5f * gb,
                10f * gb, 20f * gb, 50f * gb, 100f * gb,
                200f * gb, 500f * gb, 1000f * gb
            )
            val step = stepCandidates.first { s -> (rawMax / s).toInt() <= 6 }
            plotMax = kotlin.math.ceil(rawMax / step).toInt().coerceAtLeast(1) * step
            val vals = mutableListOf<Float>()
            var v = step
            while (v <= plotMax + step * 0.01f) { vals.add(v); v += step }
            gridValues = vals
        } else {
            val mbps1 = 125f
            val mbps3 = 375f
            plotMax = if (rawMax < mbps3) mbps3 else rawMax * 1.25f
            gridValues = if (plotMax <= mbps3 * 1.05f) {
                listOf(mbps1, mbps1 * 2f, mbps3)
            } else {
                listOf(plotMax / 3f, plotMax * 2f / 3f, plotMax)
            }
        }

        // 水平グリッド線とY軸ラベル
        gridValues.forEach { value ->
            val frac = value / plotMax
            val y = padT + plotPadT + plotH * (1f - frac)
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
            canvas.drawText(yLabelFormatter(value), 2f, y + labelPaint.textSize / 3f, labelPaint)
        }

        // ベースライン
        val baseY = padT + plotPadT + plotH
        canvas.drawLine(padL, baseY, w - padR, baseY, baselinePaint)

        // データがない場合
        if (wifiRates.isEmpty()) {
            if (!barMode) {
                canvas.drawText(
                    "接続するとデータが表示されます",
                    padL + graphW / 2f,
                    padT + graphH / 2f + noDataPaint.textSize / 3f,
                    noDataPaint
                )
            }
            drawXLabels(canvas, padL, graphW, h, padB, hasXLabels)
            return
        }

        canvas.save()
        canvas.clipRect(padL, padT, w - padR, h - padB)

        if (barMode) {
            drawBars(canvas, plotMax, padL, padT, graphW, plotH, plotPadT, baseY)
        } else {
            drawPolyline(canvas, wifiRates, plotMax, padL, padT, graphW, plotH, plotPadT, wifiLinePaint)
            drawPolyline(canvas, simRates,  plotMax, padL, padT, graphW, plotH, plotPadT, simLinePaint)
        }

        canvas.restore()

        drawXLabels(canvas, padL, graphW, h, padB, hasXLabels)
    }

    private fun drawXLabels(
        canvas: Canvas,
        padL: Float,
        graphW: Float,
        h: Float,
        padB: Float,
        hasXLabels: Boolean
    ) {
        if (hasXLabels) {
            val count = xAxisLabels.size.coerceAtLeast(1)
            val step = graphW / count.toFloat()
            val labelY = h - padB + xLabelPaint.textSize + 4f
            xAxisLabels.forEachIndexed { i, label ->
                if (label.isNotEmpty()) {
                    val cx = padL + step * (i + 0.5f)
                    canvas.drawText(label, cx, labelY, xLabelPaint)
                }
            }
        } else {
            canvas.drawText(xLabelLeft,  padL + 2f,      h - 4f, labelPaint)
            canvas.drawText(xLabelRight, padL + graphW - 30f, h - 4f, labelPaint)
        }
    }

    private fun drawBars(
        canvas: Canvas,
        maxRate: Float,
        padL: Float,
        padT: Float,
        graphW: Float,
        plotH: Float,
        plotPadT: Float,
        baseY: Float
    ) {
        val count = wifiRates.size
        if (count == 0) return

        val step = graphW / count.toFloat()
        val barWidth = (step * 0.75f).coerceAtLeast(1f)

        for (i in 0 until count) {
            val x = padL + step * i
            val wifiH = plotH * (wifiRates[i] / maxRate)
            val simH  = plotH * (simRates[i]  / maxRate)

            // WiFi バー（下段）
            if (wifiH > 0f) {
                canvas.drawRect(x, baseY - wifiH, x + barWidth, baseY, wifiBarPaint)
            }
            // SIM バー（WiFiの上に積み上げ）
            if (simH > 0f) {
                canvas.drawRect(x, baseY - wifiH - simH, x + barWidth, baseY - wifiH, simBarPaint)
            }
        }
    }

    private fun drawPolyline(
        canvas: Canvas,
        data: ArrayDeque<Float>,
        maxRate: Float,
        padL: Float,
        padT: Float,
        graphW: Float,
        plotH: Float,
        plotPadT: Float,
        paint: Paint
    ) {
        if (data.isEmpty()) return

        val step   = graphW / (MAX_POINTS - 1).toFloat()
        val startX = padL + step * (MAX_POINTS - data.size).toFloat()

        fun rateToY(rate: Float): Float =
            padT + plotPadT + plotH * (1f - rate / maxRate)

        if (data.size == 1) {
            val y = rateToY(data[0])
            canvas.drawLine(startX, y, startX + step, y, paint)
            return
        }

        val path = Path()
        data.forEachIndexed { index, rate ->
            val x = startX + step * index
            val y = rateToY(rate)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    /** Y軸ラベルのフォーマット関数。差し替え可能（デフォルトはKB/s → bps換算表示） */
    var yLabelFormatter: (Float) -> String = { kbs ->
        val kbps = kbs * 8f
        when {
            kbps >= 1_000_000f -> "%.1fGbps".format(kbps / 1_000_000f)
            kbps >= 1_000f     -> "%.0fMbps".format(kbps / 1_000f)
            else               -> "%.0fKbps".format(kbps)
        }
    }
}
