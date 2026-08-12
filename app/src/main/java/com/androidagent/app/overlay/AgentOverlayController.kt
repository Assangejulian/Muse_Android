package com.androidagent.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.androidagent.app.R
import com.androidagent.app.accessibility.AgentController
import com.androidagent.app.agent.AgentUiState
import com.androidagent.app.privileged.PrivilegedBackendRouter

/**
 * Compact system-wide run monitor shown while Muse drives another app.
 *
 * Status text is a pass-through overlay so bottom app tabs stay tappable.
 * Only the small Stop chip consumes touches.
 */
class AgentOverlayController(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var statusBar: View? = null
    private var stopChip: View? = null
    private var statusText: TextView? = null
    private var summaryText: TextView? = null
    private var thoughtScroll: ScrollView? = null
    private var chainText: TextView? = null
    private var lastSummary = ""

    fun render(state: AgentUiState) {
        if (!state.running) {
            hide()
            return
        }
        if (statusBar == null) show()
        val route = if (PrivilegedBackendRouter.isReady()) "SHIZUKU PRIMARY" else "A11Y NODE"
        statusText?.text = service.getString(R.string.agent_overlay_status, route)
        chainText?.text = service.getString(
            R.string.agent_overlay_budget,
            state.step,
            state.maxSteps,
            statusLabel(state.status),
        )
        val summary = state.thoughtLines.takeLast(10).joinToString("\n")
            .ifBlank { state.progressSummaries.takeLast(6).joinToString("\n") }
            .ifBlank { state.currentAction.ifBlank { "正在准备任务环境" } }
        if (summary.isNotBlank() && summary != lastSummary) {
            lastSummary = summary
            summaryText?.text = summary
            thoughtScroll?.post { thoughtScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun hide() {
        statusBar?.let { runCatching { windowManager.removeView(it) } }
        stopChip?.let { runCatching { windowManager.removeView(it) } }
        statusBar = null
        stopChip = null
        statusText = null
        summaryText = null
        thoughtScroll = null
        chainText = null
        lastSummary = ""
    }

    fun setCaptureHidden(hidden: Boolean) {
        val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        statusBar?.visibility = visibility
        stopChip?.visibility = visibility
    }

    private fun show() {
        val density = service.resources.displayMetrics.density
        val stopWidth = (76 * density).toInt()
        val stopHeight = (44 * density).toInt()

        val container = FrameLayout(service).apply {
            setPadding((10 * density).toInt(), (6 * density).toInt(), stopWidth + (18 * density).toInt(), (8 * density).toInt())
        }
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (9 * density).toInt(), (14 * density).toInt(), (9 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(Color.rgb(24, 24, 37))
                setStroke((1f * density).toInt(), Color.rgb(69, 71, 90))
            }
        }
        statusText = TextView(service).apply {
            setText(R.string.agent_overlay_operating)
            setTextColor(Color.rgb(180, 190, 254))
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
            letterSpacing = 0.08f
        }
        chainText = TextView(service).apply {
            setText(R.string.agent_overlay_budget_initial)
            setTextColor(Color.rgb(203, 166, 247))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        }
        summaryText = TextView(service).apply {
            text = "正在准备任务环境"
            setTextColor(Color.rgb(205, 214, 244))
            textSize = 12.5f
            typeface = Typeface.SANS_SERIF
            maxLines = 12
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(3f, 1.06f)
        }
        val scroll = ScrollView(service).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                summaryText,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        thoughtScroll = scroll
        bar.addView(statusText)
        bar.addView(chainText)
        bar.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (168 * density).toInt()),
        )
        container.addView(
            bar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        statusBar = container
        windowManager.addView(
            container,
            overlayParams(
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.WRAP_CONTENT,
                gravity = Gravity.BOTTOM,
                touchable = false,
            ),
        )

        val stop = Button(service).apply {
            setText(R.string.agent_overlay_stop)
            isAllCaps = true
            setTextColor(Color.rgb(17, 17, 27))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 11f
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.rgb(243, 139, 168))
            }
            setOnClickListener { AgentController.stop() }
        }
        stopChip = stop
        windowManager.addView(
            stop,
            overlayParams(
                width = stopWidth,
                height = stopHeight,
                gravity = Gravity.BOTTOM or Gravity.END,
                touchable = true,
            ).apply {
                x = (10 * density).toInt()
                y = (14 * density).toInt()
            },
        )
    }

    private fun overlayParams(
        width: Int,
        height: Int,
        gravity: Int,
        touchable: Boolean,
    ): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply { this.gravity = gravity }
    }

    private fun statusLabel(status: String): String = when (status) {
        "Preparing" -> "Preparing"
        "Compiling" -> "Preparing"
        "Observing" -> "Observing"
        "Planning" -> "Planning"
        "Acting" -> "Running"
        "Critiquing" -> "Checking"
        "Verifying" -> "Verifying"
        "Replanning" -> "Rerouting"
        "Stopping" -> "Stopping"
        else -> status.substringBefore(':').take(12)
    }
}
