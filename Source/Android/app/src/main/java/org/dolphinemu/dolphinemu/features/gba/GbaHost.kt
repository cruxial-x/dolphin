// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.gba

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.annotation.Keep
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Kotlin side of the native GBAHost, which is created for each integrated GBA (mGBA core).
 *
 * The callbacks from native code arrive on emulation threads. Lifecycle changes are forwarded to
 * [Listener]s on the main thread, while frame notifications go straight to the [FrameListener]
 * of the corresponding GBA so that they never wait on the main thread.
 */
object GbaHost {
    const val MAX_GBAS = 4

    interface Listener {
        /** Called on the main thread whenever [activeGbas] changes. */
        fun onGbasChanged()
    }

    fun interface FrameListener {
        /** Called on the GBA's emulation thread when a new frame is available from [getFrame]. */
        fun onFrameAvailable()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val frameListeners = AtomicReferenceArray<FrameListener?>(MAX_GBAS)

    // Only accessed on the main thread.
    private val activeGbas = arrayOfNulls<Size>(MAX_GBAS)

    /** Returns the frame size of the GBA on the given port, or null if there's no GBA there. */
    fun getActiveGba(deviceNumber: Int): Size? = activeGbas[deviceNumber]

    /** Returns the lowest port number with an active GBA, or -1 if there is none. */
    fun getFirstActiveGba(): Int = activeGbas.indexOfFirst { it != null }

    fun addListener(listener: Listener) = listeners.add(listener)

    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun setFrameListener(deviceNumber: Int, listener: FrameListener?) =
        frameListeners.set(deviceNumber, listener)

    private fun setActiveGba(deviceNumber: Int, size: Size?) {
        mainHandler.post {
            activeGbas[deviceNumber] = size
            listeners.forEach { it.onGbasChanged() }
        }
    }

    @Keep
    @JvmStatic
    fun onHostCreated(deviceNumber: Int, width: Int, height: Int) =
        setActiveGba(deviceNumber, Size(width, height))

    @Keep
    @JvmStatic
    fun onHostDestroyed(deviceNumber: Int) = setActiveGba(deviceNumber, null)

    @Keep
    @JvmStatic
    fun onGameChanged(deviceNumber: Int, width: Int, height: Int) =
        setActiveGba(deviceNumber, Size(width, height))

    @Keep
    @JvmStatic
    fun onFrameEnded(deviceNumber: Int) {
        frameListeners.get(deviceNumber)?.onFrameAvailable()
    }

    /**
     * Copies the newest frame of the given GBA into [bitmap], which must be an ARGB_8888 bitmap
     * with the GBA's current frame size. Returns false if no frame was copied.
     */
    @JvmStatic
    external fun getFrame(deviceNumber: Int, bitmap: Bitmap): Boolean
}
