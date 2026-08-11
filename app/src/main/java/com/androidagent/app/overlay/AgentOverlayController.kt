package com.androidagent.app.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.androidagent.app.R
import com.androidagent.app.accessibility.AgentController
import com.androidagent.app.agent.AgentUiState

/**
 * System-wide cyberpunk HUD while the agent drives other apps.
 * Uses TYPE_ACCESSIBILITY_OVERLAY so the progress bar stays visible outside Muse.
 */
class AgentOverlayController(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var borderView: IntelligenceBorderView? = null
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
        if (borderView == null) show()
        statusText?.text = service.getString(R.string.agent_overlay_status, state.step, statusLabel(state.status))
        chainText?.text = "EXEC_CHAIN ${state.step.toString().padStart(2, '0')}/${state.maxSteps}"
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
        borderView?.let { runCatching { windowManager.removeView(it) }; it.stop() }
        controlBar?.let { runCatching { windowManager.removeView(it) } }
        borderView = null
        controlBar = null
        statusText = null
        summaryText = null
        chainText = null
        lastSummary = ""
    }

    fun setCaptureHidden(hidden: Boolean) {
        val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        borderView?.visibility = visibility
        controlBar?.visibility = visibility
    }

    private fun show() {
        borderView = IntelligenceBorderView(service).also { view ->
            windowManager.addView(
                view,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ),
            )
            view.start()
        }

        val density = service.resources.displayMetrics.density
        val container = FrameLayout(service).apply {
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        }
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply {
                // Cut-corner illusion via flat fill + neon stroke (cyber HUD).
                cornerRadii = floatArrayOf(
                    0f, 0f,
                    14 * density, 14 * density,
                    0f, 0f,
                    14 * density, 14 * density,
                )
                setColor(Color.argb(242, 1, 4, 8))
                setStroke((1.5f * density).toInt(), Color.argb(200, 0, 240, 255))
            }
        }
        val progressColumn = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusText = TextView(service).apply {
            setText(R.string.agent_overlay_operating)
            setTextColor(Color.rgb(0, 240, 255))
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
            letterSpacing = 0.08f
        }
        chainText = TextView(service).apply {
            text = "EXEC_CHAIN 00/120"
            setTextColor(Color.rgb(255, 61, 242))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        }
        summaryText = TextView(service).apply {
            text = "正在准备任务环境"
            setTextColor(Color.rgb(233, 252, 255))
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
            setTextColor(Color.rgb(1, 4, 8))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 11f
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    0f, 0f,
                    10 * density, 10 * density,
                    0f, 0f,
                    10 * density, 10 * density,
                )
                setColor(Color.rgb(255, 65, 108))
            }
            setOnClickListener { AgentController.stop() }
        }
        bar.addView(progressColumn)
        bar.addView(stop, LinearLayout.LayoutParams((88 * density).toInt(), (48 * density).toInt()))
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
        "Preparing" -> "PREP"
        "Compiling" -> "COMPILE"
        "Observing" -> "OBSERVE"
        "Planning" -> "PLAN"
        "Acting" -> "ACT"
        "Critiquing" -> "CRITIC"
        "Verifying" -> "VERIFY"
        "Replanning" -> "REPLAN"
        "Stopping" -> "ABORT"
        else -> status.substringBefore(':').take(12).uppercase()
    }
}

private class IntelligenceBorderView(context: android.content.Context) : View(context) {
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * resources.displayMetrics.density
    }
    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = resources.displayMetrics.density }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.SQUARE
        color = Color.rgb(255, 61, 242)
    }
    private val frame = RectF()
    private var phase = 0f
    private var gradient: LinearGradient? = null
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun start() = animator.start()
    fun stop() = animator.cancel()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        gradient = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                Color.argb(40, 0, 240, 255),
                Color.rgb(0, 240, 255),
                Color.rgb(255, 61, 242),
                Color.argb(40, 255, 61, 242),
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        canvas.drawColor(Color.argb((4 + phase * 6).toInt(), 1, 4, 8))
        frame.set(6 * density, 6 * density, width - 6 * density, height - 6 * density)

        ambientPaint.color = Color.argb((18 + phase * 20).toInt(), 0, 240, 255)
        ambientPaint.strokeWidth = (12 + phase * 6) * density
        canvas.drawRoundRect(frame, 4 * density, 4 * density, ambientPaint)

        framePaint.alpha = (160 + phase * 70).toInt()
        framePaint.shader = gradient
        framePaint.strokeWidth = (2.2f + phase * 1.2f) * density
        canvas.drawRoundRect(frame, 4 * density, 4 * density, framePaint)

        val arm = 28 * density
        val left = frame.left
        val top = frame.top
        val right = frame.right
        val bottom = frame.bottom
        // Corner brackets — cyber HUD.
        canvas.drawLine(left, top + arm, left, top, cornerPaint)
        canvas.drawLine(left, top, left + arm, top, cornerPaint)
        canvas.drawLine(right - arm, top, right, top, cornerPaint)
        canvas.drawLine(right, top, right, top + arm, cornerPaint)
        canvas.drawLine(left, bottom - arm, left, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left + arm, bottom, cornerPaint)
        canvas.drawLine(right - arm, bottom, right, bottom, cornerPaint)
        canvas.drawLine(right, bottom - arm, right, bottom, cornerPaint)

        val scanY = height * phase
        scanPaint.shader = LinearGradient(
            0f, scanY, width.toFloat(), scanY,
            intArrayOf(Color.TRANSPARENT, Color.argb(100, 0, 240, 255), Color.argb(70, 255, 61, 242), Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawLine(24 * density, scanY, width - 24 * density, scanY, scanPaint)
    }
}
