// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.gba

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.utils.Log

/**
 * Shows the screen of the first active integrated GBA on a secondary display (such as the bottom
 * screen of a dual-screen handheld) while [activity] is started, unless the user has disabled it.
 */
class GbaDisplayController(private val activity: ComponentActivity) : DefaultLifecycleObserver,
    GbaHost.Listener, DisplayManager.DisplayListener {
    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var presentation: GbaPresentation? = null
    private var started = false

    override fun onStart(owner: LifecycleOwner) {
        started = true
        GbaHost.addListener(this)
        displayManager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        update()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        GbaHost.removeListener(this)
        displayManager.unregisterDisplayListener(this)
        dismiss()
    }

    override fun onGbasChanged() = update()

    override fun onDisplayAdded(displayId: Int) = update()

    override fun onDisplayRemoved(displayId: Int) = update()

    override fun onDisplayChanged(displayId: Int) = update()

    private fun update() {
        if (!started)
            return

        val deviceNumber = GbaHost.getFirstActiveGba()
        val display = if (deviceNumber >= 0 && BooleanSetting.MAIN_GBA_SECONDARY_DISPLAY.boolean)
            findSecondaryDisplay()
        else
            null
        if (display == null) {
            dismiss()
            return
        }

        val current = presentation
        if (current == null || current.display.displayId != display.displayId ||
            current.deviceNumber != deviceNumber
        ) {
            dismiss()
            try {
                presentation = GbaPresentation(activity, display, deviceNumber).apply { show() }
            } catch (e: WindowManager.InvalidDisplayException) {
                Log.warning("[GbaDisplayController] Couldn't show GBA on display ${display.displayId}: $e")
                return
            }
        }
        presentation!!.setFrameSize(GbaHost.getActiveGba(deviceNumber)!!)
    }

    private fun dismiss() {
        presentation?.dismiss()
        presentation = null
    }

    private fun findSecondaryDisplay(): Display? {
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val candidates = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter {
                it.displayId != Display.DEFAULT_DISPLAY && it.isValid && it.state == Display.STATE_ON
            }

        // Some dual-screen handhelds also expose a virtual presentation display that mirrors the
        // built-in screen, which is given the built-in screen's name. Prefer the real one.
        return candidates.firstOrNull { it.name != defaultDisplay?.name } ?: candidates.firstOrNull()
    }
}
