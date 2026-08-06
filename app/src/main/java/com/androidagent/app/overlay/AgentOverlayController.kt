package com.androidagent.app.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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

class AgentOverlayController(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var borderView: IntelligenceBorderView? = null
    private var controlBar: View? = null
    private var statusText: TextView? = null
    private var summaryText: TextView? = null
    private var lastSummary = ""

    fun render(state: AgentUiState) {
        if (!state.running) {
            hide()
            return
        }
        if (borderView == null) show()
        statusText?.text = service.getString(R.string.agent_overlay_status, state.step, statusLabel(state.status))
        val summary = state.progressSummaries.takeLast(2).joinToString("\n")
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
        lastSummary = ""
    }

    fun setCaptureHidden(hidden: Boolean) {
        val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        borderView?.visibility = visibility
        controlBar?.visibility = visibility
    }

    private fun show() {
        borderView = IntelligenceBorderView(service).also { view ->
            windowManager.addView(view, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    // Planning pauses run longer than a typical display timeout. If the
                    // screen sleeps mid-run, gestures are cancelled and screenshots fail,
                    // so the run dies of failed actions rather than of anything real.
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.TRANSLUCENT,
            ))
            view.start()
        }

        val density = service.resources.displayMetrics.density
        val container = FrameLayout(service).apply {
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        val bar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 22 * density
                setColor(Color.argb(246, 11, 19, 18))
                setStroke((1 * density).toInt(), Color.argb(115, 94, 244, 202))
            }
        }
        val progressColumn = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusText = TextView(service).apply {
            setText(R.string.agent_overlay_operating)
            setTextColor(Color.rgb(102, 246, 204))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        summaryText = TextView(service).apply {
            text = "正在准备任务环境"
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
        }
        progressColumn.addView(statusText)
        progressColumn.addView(summaryText)
        val stop = Button(service).apply {
            setText(R.string.agent_overlay_stop)
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(Color.rgb(165, 48, 55))
            }
            setOnClickListener { AgentController.stop() }
        }
        bar.addView(progressColumn)
        bar.addView(stop, LinearLayout.LayoutParams((84 * density).toInt(), (44 * density).toInt()))
        container.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (88 * density).toInt()))
        controlBar = container
        windowManager.addView(container, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (108 * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM })
    }

    private fun statusLabel(status: String): String = when (status) {
        "Preparing" -> "正在准备"
        "Compiling" -> "快速拆解任务"
        "Observing" -> "读取页面"
        "Planning" -> "选择下一步"
        "Acting" -> "执行操作"
        "Critiquing" -> "检查结果"
        "Verifying" -> "最终验收"
        "Replanning" -> "切换策略"
        else -> status.substringBefore(':')
    }
}

private class IntelligenceBorderView(context: android.content.Context) : View(context) {
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
    }
    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = resources.displayMetrics.density }
    private val frame = RectF()
    private var phase = 0f
    private var gradient: LinearGradient? = null
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun start() = animator.start()
    fun stop() = animator.cancel()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        gradient = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                Color.argb(30, 70, 255, 211),
                Color.rgb(73, 218, 255),
                Color.rgb(94, 255, 204),
                Color.argb(30, 70, 255, 211),
            ),
            floatArrayOf(0f, .38f, .7f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        canvas.drawColor(Color.argb((5 + phase * 5).toInt(), 5, 20, 17))
        frame.set(5 * density, 5 * density, width - 5 * density, height - 5 * density)

        ambientPaint.color = Color.argb((24 + phase * 18).toInt(), 78, 244, 204)
        ambientPaint.strokeWidth = (14 + phase * 8) * density
        canvas.drawRoundRect(frame, 28 * density, 28 * density, ambientPaint)

        framePaint.alpha = (175 + phase * 70).toInt()
        framePaint.shader = gradient
        framePaint.strokeWidth = (2.4f + phase * 1.4f) * density
        canvas.drawRoundRect(frame, 26 * density, 26 * density, framePaint)

        val scanY = height * phase
        scanPaint.shader = LinearGradient(
            0f, scanY, width.toFloat(), scanY,
            intArrayOf(Color.TRANSPARENT, Color.argb(90, 92, 255, 211), Color.TRANSPARENT),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawLine(24 * density, scanY, width - 24 * density, scanY, scanPaint)
    }
}
