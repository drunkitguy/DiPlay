package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** Head-unit style spinner for the screen before CarPlay's picture: a faint track and one turning arc. */
internal class ConnectingRing(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x1FFFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xE6FFFFFF.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // No frames drawn under CarPlay's picture.
        if (isShown) { if (!animator.isStarted) animator.start() } else animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = minOf(width, height) * 0.06f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        bounds.set(stroke, stroke, width - stroke, height - stroke)
        canvas.drawOval(bounds, track)
        // The arc grows and shrinks once per turn, like most head units' boot spinners.
        val sweep = 40f + 80f * (0.5f - 0.5f * kotlin.math.cos(phase * 2 * Math.PI).toFloat())
        canvas.drawArc(bounds, phase * 360f - 90f, sweep, false, arc)
    }
}
