// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.gba

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Size
import android.view.View
import kotlin.math.floor
import kotlin.math.min

/**
 * Shows the screen of one integrated GBA, scaled by the largest integer factor that fits the view
 * (or scaled to fit, if the view is smaller than the GBA screen) with nearest-neighbor filtering.
 */
class GbaScreenView(context: Context, private val deviceNumber: Int) : View(context),
    GbaHost.FrameListener {
    private val paint = Paint().apply { isFilterBitmap = false }
    private val destRect = Rect()
    private var bitmap: Bitmap? = null

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
    }
}
