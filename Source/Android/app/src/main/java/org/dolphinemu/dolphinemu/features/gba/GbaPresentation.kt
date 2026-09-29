// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.gba

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Size
import android.view.Display
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Shows the screen of one integrated GBA on a secondary display. */
class GbaPresentation(outerContext: Context, display: Display, val deviceNumber: Int) :
    Presentation(outerContext, display) {
    private lateinit var screenView: GbaScreenView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Never take input focus, so that controller input keeps going to EmulationActivity even
        // after the secondary display has been touched.
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        screenView = GbaScreenView(context, deviceNumber)
        setContentView(screenView)

        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            WindowInsetsControllerCompat(it, it.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    fun setFrameSize(size: Size) = screenView.setFrameSize(size)
}
