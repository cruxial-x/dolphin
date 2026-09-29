// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.gba

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Size
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.min

/**
 * Shows the screen of one integrated GBA, scaled by the largest integer factor that fits the view
 * (or scaled to fit, if the view is smaller than the GBA screen) with nearest-neighbor filtering.
 *
 * Touching the view gives the GBAs the controller input focus.
 */
class GbaScreenView(context: Context, private val deviceNumber: Int) : View(context),
    GbaHost.FrameListener {
    private val paint = Paint().apply { isFilterBitmap = false }
    private val destRect = Rect()
    private var bitmap: Bitmap? = null
    private val focusPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(0xA0, 0xFF, 0xFF, 0xFF)
        strokeWidth =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics)
    }

    /** Whether to outline the screen to show that the GBAs have the controller input focus. */
    var showInputFocus = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        setBackgroundColor(Color.BLACK)
    }

    /** Must be called on the main thread whenever the GBA's frame size may have changed. */
    fun setFrameSize(size: Size) {
        val current = bitmap
        if (current != null && current.width == size.width && current.height == size.height)
            return

        bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        updateDestRect()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GbaHost.setFrameListener(deviceNumber, this)
    }

    override fun onDetachedFromWindow() {
        GbaHost.setFrameListener(deviceNumber, null)
        super.onDetachedFromWindow()
    }

    override fun onFrameAvailable() = postInvalidateOnAnimation()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDestRect()
    }

    private fun updateDestRect() {
        val frame = bitmap ?: return
        val fitScale = min(width.toFloat() / frame.width, height.toFloat() / frame.height)
        // Integer scaling keeps every GBA pixel the same size. Only fall back to fractional
        // scaling if the view can't even fit the GBA screen at 1x.
        val scale = if (fitScale >= 1) floor(fitScale) else fitScale
        val scaledWidth = (frame.width * scale).toInt()
        val scaledHeight = (frame.height * scale).toInt()
        val left = (width - scaledWidth) / 2
        val top = (height - scaledHeight) / 2
        destRect.set(left, top, left + scaledWidth, top + scaledHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = bitmap ?: return
        GbaHost.getFrame(deviceNumber, frame)
        canvas.drawBitmap(frame, null, destRect, paint)

        if (showInputFocus) {
            val inset = -focusPaint.strokeWidth
            canvas.drawRect(
                destRect.left + inset, destRect.top + inset,
                destRect.right - inset, destRect.bottom - inset, focusPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> GbaHost.setInputFocus(true)
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
