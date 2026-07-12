package com.androidagent.app.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.androidagent.app.accessibility.AgentController
import com.androidagent.app.agent.AgentUiState

class AgentOverlayController(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var borderView: IntelligenceBorderView? = null
    private var controlBar: View? = null
    private var statusText: TextView? = null

    fun render(state: AgentUiState) {
        if (!state.running) {
            hide()
            return
        }
        if (borderView == null) show()
        statusText?.text = "第 ${state.step} 步 · ${statusLabel(state.status)}"
    }

    fun hide() {
        borderView?.let { runCatching { windowManager.removeView(it) }; it.stop() }
        controlBar?.let { runCatching { windowManager.removeView(it) } }
        borderView = null
        controlBar = null
        statusText = null
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
            setPadding((18 * density).toInt(), 0, (8 * density).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(Color.argb(235, 18, 25, 22))
                setStroke((1 * density).toInt(), Color.argb(90, 145, 255, 203))
            }
        }
        statusText = TextView(service).apply {
            text = "AI 正在操作"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val stop = Button(service).apply {
            text = "停止"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(Color.rgb(165, 48, 55))
            }
            setOnClickListener { AgentController.stop() }
        }
        bar.addView(statusText)
        bar.addView(stop, LinearLayout.LayoutParams((84 * density).toInt(), (44 * density).toInt()))
        container.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (56 * density).toInt()))
        controlBar = container
        windowManager.addView(container, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (76 * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP })
    }

    private fun statusLabel(status: String): String = when (status) {
        "Preparing" -> "正在准备"
        "Observing" -> "正在读取屏幕"
        "Planning" -> "正在思考下一步"
        "Acting" -> "正在执行操作"
        else -> status.substringBefore(':')
    }
}

private class IntelligenceBorderView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7 * resources.displayMetrics.density
    }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun start() = animator.start()
    fun stop() = animator.cancel()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.alpha = (150 + phase * 95).toInt()
        paint.strokeWidth = (5 + phase * 3) * resources.displayMetrics.density
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(Color.rgb(72, 210, 255), Color.rgb(152, 92, 255), Color.rgb(72, 255, 181), Color.rgb(72, 210, 255)),
            floatArrayOf(0f, .34f, .68f, 1f), Shader.TileMode.CLAMP,
        )
        val inset = paint.strokeWidth / 2
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, 24 * resources.displayMetrics.density, 24 * resources.displayMetrics.density, paint)
    }
}
