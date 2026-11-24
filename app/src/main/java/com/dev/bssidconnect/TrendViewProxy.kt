package com.dev.bssidconnect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * v18：实时仪表盘的趋势图宿主视图（XML 可实例化的非内部类）。
 * 绘制逻辑与弹窗内 TrendView 相同（读 GuardState.samples），抽成顶层类供
 * activity_main.xml 的 dashTrend 直接以 XML 类名实例化；MainActivity 在 uiSync
 * 轮询里 invalidate() 驱动滚动。布局 inflate 时宿主 Activity 未必已构造完成，
 * 因此不持有 Activity 引用，全部数据取自 GuardState 单例。
 */
class TrendViewProxy(ctx: Context) : View(ctx) {
    /** XML 膨胀必需的 (Context, AttributeSet) 构造器（NoSuchMethodException 修复） */
    constructor(ctx: Context, attrs: android.util.AttributeSet?) : this(ctx)

    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f }
    private val pGrid = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x33000000.toInt() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, pGrid)
        val s = GuardState.samples.toList()
        if (s.isEmpty()) {
            pText.color = androidx.core.content.ContextCompat.getColor(context, R.color.chart_tick_text)
            canvas.drawText("趋势图：守护探活每 5s 采样 RSSI/延迟", 20f, h / 2, pText)
            return
        }
        val rssis = s.mapNotNull { it.rssi }
        val rtts = s.mapNotNull { it.rtt }
        val rssiMin = (rssis.minOrNull() ?: -100) - 2
        val rssiMax = (rssis.maxOrNull() ?: -40) + 2
        val rttMax = ((rtts.maxOrNull() ?: 100) * 1.2 + 10).toFloat()
        fun yRssi(v: Int) = h - ((v - rssiMin).toFloat() / ((rssiMax - rssiMin).coerceAtLeast(1))) * (h - 40) - 20
        fun yRtt(v: Long) = h - (v / rttMax) * (h - 40) - 20
        // 时间刻度
        pText.color = androidx.core.content.ContextCompat.getColor(context, R.color.chart_axis_text)
        val t0 = s.first().ts; val t1 = s.last().ts
        canvas.drawText(android.text.format.DateFormat.format("HH:mm", t0).toString(), 8f, h - 4f, pText)
        val lbl = android.text.format.DateFormat.format("HH:mm", t1).toString()
        canvas.drawText(lbl, w - pText.measureText(lbl) - 8f, h - 4f, pText)
        // RSSI 绿线
        pLine.color = androidx.core.content.ContextCompat.getColor(context, R.color.trend_rssi)
        var prev: Pair<Float, Float>? = null
        for ((i, sp) in s.withIndex()) {
            val r = sp.rssi ?: continue
            val x = if (s.size == 1) w / 2 else (i.toFloat() / (s.size - 1)) * (w - 20) + 10
            val y = yRssi(r)
            prev?.let { canvas.drawLine(it.first, it.second, x, y, pLine) }
            prev = x to y
        }
        // 延迟蓝线
        pLine.color = androidx.core.content.ContextCompat.getColor(context, R.color.trend_rtt)
        prev = null
        for ((i, sp) in s.withIndex()) {
            val r = sp.rtt ?: continue
            val x = if (s.size == 1) w / 2 else (i.toFloat() / (s.size - 1)) * (w - 20) + 10
            val y = yRtt(r)
            prev?.let { canvas.drawLine(it.first, it.second, x, y, pLine) }
            prev = x to y
        }
        // 图例
        pText.color = androidx.core.content.ContextCompat.getColor(context, R.color.trend_rssi)
        canvas.drawText("RSSI ${rssis.lastOrNull() ?: "-"}dBm", 8f, 30f, pText)
        pText.color = androidx.core.content.ContextCompat.getColor(context, R.color.trend_rtt)
        canvas.drawText("RTT ${rtts.lastOrNull() ?: "-"}ms", 260f, 30f, pText)
    }
}
