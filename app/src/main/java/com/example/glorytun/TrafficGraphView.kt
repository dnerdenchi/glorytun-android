package com.example.glorytun

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * WiFi と SIM の通信スループット (KB/s) を折れ線グラフで表示するカスタムView。
 * - 青い線: WiFi (TX+RX合計)
 * - オレンジの線: SIM (TX+RX合計)
 * - 最大60秒分のデータを保持し、右端が最新。左端が60秒前。
 * - 両方のデータ系列は常に同時に addDataPoint で追加されるため、
 *   片方が 0 でも常に2本の線が表示される。
 */
class TrafficGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_POINTS = 60
    }

    private val wifiRates = ArrayDeque<Float>()
    private val simRates  = ArrayDeque<Float>()

    private val wifiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#2196F3")  // Blue
        strokeWidth = 4f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val simPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#FF9800")  // Orange
        strokeWidth = 4f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#DDDDDD")
        strokeWidth = 1f
        style       = Paint.Style.STROKE
    }

    // 0KB/s ラインを枠と区別するための基準線
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#BBBBBB")
        strokeWidth = 1.5f
        style       = Paint.Style.STROKE
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F8F8F8")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.parseColor("#888888")
        textSize = 26f
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#AAAAAA")
        textSize  = 32f
        textAlign = Paint.Align.CENTER
    }

    /** 新しいデータ点を追加する (KB/s 単位)。addDataPoint は常に両方の系列に追加する。 */
    fun addDataPoint(wifiKBs: Float, simKBs: Float) {
        if (wifiRates.size >= MAX_POINTS) wifiRates.removeFirst()
        if (simRates.size  >= MAX_POINTS) simRates.removeFirst()
        wifiRates.addLast(wifiKBs.coerceAtLeast(0f))
        simRates.addLast(simKBs.coerceAtLeast(0f))
        invalidate()
    }

    /** グラフをリセットする（切断時に呼ぶ）。 */
    fun reset() {
        wifiRates.clear()
        simRates.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 外側のパディング（Y軸ラベル・X軸ラベル用）
        val padL = 72f
        val padR = 8f
        val padT = 8f
        val padB = 28f

        val graphW = w - padL - padR
        val graphH = h - padT - padB

        // プロット領域の上下マージン
        // これにより rate=0 の線が背景矩形の下端と重ならず、視認できる
        val plotPadT = 6f
        val plotPadB = 10f
        val plotH    = graphH - plotPadT - plotPadB

        // 背景
        canvas.drawRect(padL, padT, w - padR, h - padB, bgPaint)

        // Y軸スケール計算
        val allRates = wifiRates.toList() + simRates.toList()
        val maxRate = (allRates.maxOrNull() ?: 0f).let {
            if (it < 1f) 10f else it * 1.25f
        }

        // 水平グリッド線（3本）とY軸ラベル
        for (i in 1..3) {
            val frac = i.toFloat() / 3f
            val y = padT + plotPadT + plotH * (1f - frac)
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
            canvas.drawText(formatRate(maxRate * frac), 2f, y + labelPaint.textSize / 3f, labelPaint)
        }

        // 0KB/s ベースライン（背景枠の下端より plotPadB 上にある）
        val baseY = padT + plotPadT + plotH  // = padT + graphH - plotPadB
        canvas.drawLine(padL, baseY, w - padR, baseY, baselinePaint)

        // データがない場合はメッセージ表示
        if (wifiRates.isEmpty()) {
            canvas.drawText(
                "接続するとデータが表示されます",
                padL + graphW / 2f,
                padT + graphH / 2f + noDataPaint.textSize / 3f,
                noDataPaint
            )
            // X軸ラベルだけ描画して終了
            canvas.drawText("60s前", padL + 2f,      h - 4f, labelPaint)
            canvas.drawText("現在",  w - padR - 30f, h - 4f, labelPaint)
            return
        }

        // グラフ領域外に線がはみ出さないようクリッピング
        canvas.save()
        canvas.clipRect(padL, padT, w - padR, h - padB)

        drawPolyline(canvas, wifiRates, maxRate, padL, padT, graphW, plotH, plotPadT, wifiPaint)
        drawPolyline(canvas, simRates,  maxRate, padL, padT, graphW, plotH, plotPadT, simPaint)

        canvas.restore()

        // X軸ラベル
        canvas.drawText("60s前", padL + 2f,      h - 4f, labelPaint)
        canvas.drawText("現在",  w - padR - 30f, h - 4f, labelPaint)
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
        // データが少ない間は右端に寄せて表示
        val startX = padL + step * (MAX_POINTS - data.size).toFloat()

        fun rateToY(rate: Float): Float =
            padT + plotPadT + plotH * (1f - rate / maxRate)

        if (data.size == 1) {
            // 1点のみの場合: step幅の水平線として表示
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

    private fun formatRate(kbs: Float): String =
        if (kbs >= 1024f) "%.1fM".format(kbs / 1024f) else "%.0fK".format(kbs)
}
