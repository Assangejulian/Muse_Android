package com.androidagent.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.androidagent.app.R
import com.androidagent.app.accessibility.AgentController
import com.androidagent.app.agent.AgentUiState
import com.androidagent.app.privileged.PrivilegedBackendRouter

/**
 * Compact system-wide run monitor shown while Muse drives another app.
 */
class AgentOverlayController(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var controlBar: View? = null
    private var statusText: TextView? = null
    private var summaryText: TextView? = null
    private var chainText: TextView? = null
    private var lastSummary = ""

    fun render(state: AgentUiState) {
        if (!state.running) {
            hide()
            return
        }
        if (controlBar == null) show()
        val route = if (PrivilegedBackendRouter.isReady()) "SHIZUKU PRIMARY" else "A11Y NODE"
        statusText?.text = service.getString(R.string.agent_overlay_status, route)
        chainText?.text = service.getString(
            R.string.agent_overlay_budget,
            state.step,
            state.maxSteps,
            statusLabel(state.status),
        )
        val summary = state.progressSummaries.takeLast(2).joinToString("\n")
            .ifBlank { state.currentAction.ifBlank { "正在准备任务环境" } }
        if (summary.isNotBlank() && summary != lastSummary) {
            lastSummary = summary
            summaryText?.animate()?.cancel()
            summaryText?.animate()
                ?.alpha(0f)
                ?.translationY(-8 * service.resources.displayMetrics.density)
                ?.setDuration(110L)
                ?.withEndAction {
                    summaryText?.text = summary
                    summaryText?.translationY = 8 * service.resources.displayMetrics.density
                    summaryText?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(180L)?.start()
                }
                ?.start()
        }
    }

    fun hide() {
        controlBar?.let { runCatching { windowManager.removeView(it) } }
        controlBar = null
        statusText = null
        summaryText = null
        chainText = null
        lastSummary = ""
    }

    fun setCaptureHidden(hidden: Boolean) {
        val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        controlBar?.visibility = visibility
    }

    private fun show() {
        val density = service.resources.displayMetrics.density
        val container = FrameLayout(service).apply {
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
        }
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (9 * density).toInt(), (7 * density).toInt(), (9 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(Color.rgb(24, 24, 37))
                setStroke((1f * density).toInt(), Color.rgb(69, 71, 90))
            }
        }
        val progressColumn = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
            textSize = 13f
            typeface = Typeface.MONOSPACE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
        }
        progressColumn.addView(statusText)
        progressColumn.addView(chainText)
        progressColumn.addView(summaryText)
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
        bar.addView(progressColumn)
        bar.addView(stop, LinearLayout.LayoutParams((76 * density).toInt(), (44 * density).toInt()))
        container.addView(
            bar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        controlBar = container
        windowManager.addView(
            container,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM },
        )
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
