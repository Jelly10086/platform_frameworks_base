/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.externaldesktop

import android.app.ActivityTaskManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import javax.inject.Inject

@SysUISingleton
class ExternalDesktopStartable
@Inject
constructor(
    private val context: Context,
    @Main private val mainHandler: Handler,
    private val userTracker: UserTracker,
) : CoreStartable {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val externalDisplayIds = mutableSetOf<Int>()
    private var blankingView: View? = null

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refreshExternalDisplayState()
            }
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                val display = displayManager.getDisplay(displayId)
                if (isExternalDesktopDisplay(display)) externalDisplayIds.add(displayId)
                updateBlankingView()
                if (isExternalDesktopDisplay(display)) {
                    focusExternalDisplay(displayId)
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                val wasExternal = externalDisplayIds.remove(displayId)
                updateBlankingView()
                if (wasExternal && externalDisplayIds.isEmpty()) restoreBuiltInDisplay()
            }

            override fun onDisplayChanged(displayId: Int) {
                val wasExternal = externalDisplayIds.contains(displayId)
                val display = displayManager.getDisplay(displayId)
                val isExternal = isExternalDesktopDisplay(display)
                if (isExternal) {
                    externalDisplayIds.add(displayId)
                } else {
                    externalDisplayIds.remove(displayId)
                }
                updateBlankingView()
                if (!wasExternal && isExternal && isEnabled()) {
                    focusExternalDisplay(displayId)
                } else if (wasExternal && !isExternal && externalDisplayIds.isEmpty()) {
                    restoreBuiltInDisplay()
                }
            }
        }

    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                refreshExternalDisplayState()
            }
        }

    override fun start() {
        displayManager.registerDisplayListener(displayListener, mainHandler)
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.UWU_EXTERNAL_DESKTOP_ENABLED),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.UWU_EXTERNAL_DESKTOP_BLANK_INTERNAL_DISPLAY),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(
                Settings.Secure.UWU_EXTERNAL_DESKTOP_ALLOW_SCRCPY_VIRTUAL_DISPLAY,
            ),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        userTracker.addCallback(userCallback, context.mainExecutor)
        syncExternalDisplays()
        updateBlankingView()
        if (isEnabled()) focusExternalDisplay()
    }

    private fun updateBlankingView() {
        val shouldBlank =
            isEnabled() && shouldBlankInternalDisplay() && activeExternalDisplays().isNotEmpty()
        if (shouldBlank) showBlankingView() else hideBlankingView()
    }

    private fun showBlankingView() {
        if (blankingView != null) return
        val view = createBlankingView()
        try {
            windowManager.addView(view, createLayoutParams())
            blankingView = view
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to cover the built-in display", e)
        }
    }

    private fun hideBlankingView() {
        blankingView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to remove the built-in display cover", e)
            }
        }
        blankingView = null
    }

    private fun createBlankingView(): View {
        val density = context.resources.displayMetrics.density
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
                setBackgroundColor(Color.BLACK)
                isClickable = true
                setOnTouchListener { _, _ ->
                    focusExternalDisplay()
                    true
                }
                systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        container.addView(
            TextView(context).apply {
                setText(R.string.uwu_external_desktop_connected_title)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        )
        container.addView(
            TextView(context).apply {
                setText(R.string.uwu_external_desktop_connected_message)
                setTextColor(Color.argb(179, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(0, (8 * density).toInt(), 0, 0)
            }
        )
        container.contentDescription =
            context.getString(R.string.uwu_external_desktop_connected_accessibility)
        return container
    }

    private fun createLayoutParams() =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            )
            .apply {
                title = "External desktop built-in display cover"
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                privateFlags =
                    privateFlags or WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
            }

    private fun activeExternalDisplays(): List<Display> =
        displayManager.displays.filter(::isExternalDesktopDisplay)

    private fun syncExternalDisplays() {
        externalDisplayIds.clear()
        activeExternalDisplays().mapTo(externalDisplayIds) { it.displayId }
    }

    private fun refreshExternalDisplayState() {
        val hadExternalDesktop = externalDisplayIds.isNotEmpty() || blankingView != null
        syncExternalDisplays()
        updateBlankingView()
        if (externalDisplayIds.isNotEmpty()) {
            focusExternalDisplay()
        } else if (hadExternalDesktop) {
            restoreBuiltInDisplay()
        }
    }

    private fun isExternalDesktopDisplay(display: Display?): Boolean {
        if (display == null || display.displayId == Display.DEFAULT_DISPLAY) return false
        if (!isEnabled()) return false
        if (display.state == Display.STATE_OFF) return false
        return when (display.type) {
            Display.TYPE_EXTERNAL,
            Display.TYPE_OVERLAY -> true
            Display.TYPE_VIRTUAL -> {
                val isScrcpyDisplay = display.name.contains("scrcpy", ignoreCase = true)
                (!isScrcpyDisplay || allowScrcpyVirtualDisplay()) &&
                    (display.flags and Display.FLAG_PRESENTATION != 0 || isScrcpyDisplay)
            }
            else -> display.flags and Display.FLAG_PRESENTATION != 0
        }
    }

    private fun focusExternalDisplay(displayId: Int? = null) {
        val targetId =
            displayId ?: activeExternalDisplays().maxByOrNull { it.displayId }?.displayId ?: return
        FOCUS_RETRY_DELAYS.forEach { delay -> focusDisplay(targetId, delay) }
    }

    private fun focusDisplay(displayId: Int, delayMillis: Long) {
        mainHandler.postDelayed(
            {
                if (displayId != Display.DEFAULT_DISPLAY && !isEnabled()) return@postDelayed
                if (
                    displayId != Display.DEFAULT_DISPLAY &&
                        !isExternalDesktopDisplay(displayManager.getDisplay(displayId))
                ) {
                    return@postDelayed
                }
                try {
                    ActivityTaskManager.getService().focusTopTask(displayId)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to focus display $displayId", e)
                }
            },
            delayMillis,
        )
    }

    private fun restoreBuiltInDisplay() {
        hideBlankingView()
        focusDisplay(Display.DEFAULT_DISPLAY, 0)
        if (!powerManager.isInteractive) {
            powerManager.wakeUp(
                SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_APPLICATION,
                TAG,
            )
        }
    }

    private fun isEnabled() =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.UWU_EXTERNAL_DESKTOP_ENABLED,
            0,
            userTracker.userId,
        ) != 0

    private fun shouldBlankInternalDisplay() =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.UWU_EXTERNAL_DESKTOP_BLANK_INTERNAL_DISPLAY,
            1,
            userTracker.userId,
        ) != 0

    private fun allowScrcpyVirtualDisplay() =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.UWU_EXTERNAL_DESKTOP_ALLOW_SCRCPY_VIRTUAL_DISPLAY,
            1,
            userTracker.userId,
        ) != 0

    private companion object {
        const val TAG = "ExternalDesktop"
        val FOCUS_RETRY_DELAYS = longArrayOf(0, 250, 750, 1500, 3000)
    }
}
