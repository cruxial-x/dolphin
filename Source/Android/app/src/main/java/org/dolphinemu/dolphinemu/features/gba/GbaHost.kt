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

    /** Whether the GBAs, rather than the GameCube controllers, receive controller input. */
    var inputFocusOnGba = false
        private set

    /**
     * Whether [inputFocusOnGba] makes a difference, which is only the case if there's also a
     * GameCube controller for the controller input to go to.
     */
    var inputFocusMatters = false
        private set

    /** Returns the frame size of the GBA on the given port, or null if there's no GBA there. */
    fun getActiveGba(deviceNumber: Int): Size? = activeGbas[deviceNumber]

    /** Returns the lowest port number with an active GBA, or -1 if there is none. */
    fun getFirstActiveGba(): Int = activeGbas.indexOfFirst { it != null }

    fun addListener(listener: Listener) = listeners.add(listener)

    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun setFrameListener(deviceNumber: Int, listener: FrameListener?) =
        frameListeners.set(deviceNumber, listener)

    /**
     * Sends controller input to the GBAs or to the GameCube controllers. Must be called on the main
     * thread. Does nothing if there is no active GBA.
     */
    fun setInputFocus(onGba: Boolean) {
        if (getFirstActiveGba() < 0 || (onGba == inputFocusOnGba && inputFocusMatters))
            return

        inputFocusMatters = setInputFocusNative(onGba)
        inputFocusOnGba = onGba
        listeners.forEach { it.onGbasChanged() }
    }

    private fun setActiveGba(deviceNumber: Int, size: Size?) {
        mainHandler.post {
            val hadActiveGba = getFirstActiveGba() >= 0
            activeGbas[deviceNumber] = size
            if (!hadActiveGba || getFirstActiveGba() < 0) {
                // Native code gives the GameCube controllers the focus when the first GBA appears.
                inputFocusOnGba = false
                inputFocusMatters = setInputFocusNative(false)
            }
            listeners.forEach { it.onGbasChanged() }
        }
    }

    @Keep
    @JvmStatic
    fun onHostCreated(deviceNumber: Int, width: Int, height: Int) =
        setActiveGba(deviceNumber, Size(width, height))

    @Keep
    @JvmStatic
    fun onHostDestroyed(deviceNumber: Int) {
        // The host is destroyed after its GBA has stopped and closed its save.
        GbaSaveSync.onGbaStopped(deviceNumber)
        setActiveGba(deviceNumber, null)
    }

    /** Called before the GBA on the given port opens its save, which is before it has a host. */
    @Keep
    @JvmStatic
    fun onSaveOpening(deviceNumber: Int, romPath: String, savePath: String) =
        GbaSaveSync.onSaveOpening(deviceNumber, romPath, savePath)

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

    /** Returns whether there is a GameCube controller for input to go to instead of the GBAs. */
    @JvmStatic
    private external fun setInputFocusNative(onGba: Boolean): Boolean
}
