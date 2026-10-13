package com.autoagent.cms.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayManager(
    private val context: Context,
    private val onStop: () -> Unit
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var statusText: TextView? = null

    fun show(initialStatus: String): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (root != null) return true

        val status = TextView(context).apply {
            text = initialStatus
            setPadding(24, 12, 24, 12)
        }

        val stop = Button(context).apply {
            text = "Stop"
            setOnClickListener { onStop() }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(status)
            addView(stop)
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 60
        }

        return try {
            windowManager.addView(container, params)
            root = container
            statusText = status
            true
        } catch (_: Exception) {
            false
        }
    }

    fun update(status: String) {
        statusText?.text = status.take(200)
    }

    fun hide() {
        val view = root ?: return

        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // The window may already have been removed by Android.
        } finally {
            root = null
            statusText = null
        }
    }
}
