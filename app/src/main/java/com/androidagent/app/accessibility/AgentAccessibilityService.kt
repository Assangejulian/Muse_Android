package com.androidagent.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.androidagent.app.agent.AgentAction
import com.androidagent.app.agent.Observation
import com.androidagent.app.agent.UiNodeSnapshot
import com.androidagent.app.overlay.AgentOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class AgentAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlayController: AgentOverlayController

    override fun onServiceConnected() {
        instance = this
        overlayController = AgentOverlayController(this)
        serviceScope.launch { AgentController.state.collectLatest(overlayController::render) }
        AgentController.setAccessibilityConnected(true)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let(AgentController::setCurrentPackage)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        if (::overlayController.isInitialized) overlayController.hide()
        serviceScope.cancel()
        if (instance === this) instance = null
        AgentController.setAccessibilityConnected(false)
        super.onDestroy()
    }

    fun observe(): Observation {
        val nodes = mutableListOf<UiNodeSnapshot>()
        val nextId = AtomicInteger(1)
        windows.sortedByDescending { it.layer }.forEach { window ->
            window.root?.let { root ->
                if (ObservationPolicy.shouldIncludePackage(root.packageName?.toString())) {
                    collectNodes(root, nodes, nextId, 0)
                }
            }
        }
        return Observation(rootInActiveWindow?.packageName?.toString().orEmpty(), nodes.take(MAX_NODES))
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<UiNodeSnapshot>,
        nextId: AtomicInteger,
        depth: Int,
    ) {
        if (output.size >= MAX_NODES || depth > MAX_DEPTH) return
        val rect = Rect().also(node::getBoundsInScreen)
        val id = nextId.getAndIncrement()
        output += UiNodeSnapshot(
            id = id,
            text = if (node.isPassword) "" else node.text?.toString().orEmpty(),
            description = node.contentDescription?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            clickable = node.isClickable,
            editable = node.isEditable,
            bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
        )
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectNodes(child, output, nextId, depth + 1)
            }
        }
    }

    suspend fun execute(action: AgentAction, observation: Observation): Boolean = when (action) {
        is AgentAction.LaunchApp -> launchApp(action.packageName)
        is AgentAction.ClickText -> clickText(action.text)
        is AgentAction.ClickNode -> clickNode(action.nodeId, observation)
        is AgentAction.Swipe -> swipe(action.direction)
        is AgentAction.InputText -> inputText(action.text)
        is AgentAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
        is AgentAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
        is AgentAction.Wait -> true
        is AgentAction.Finish, is AgentAction.Fail -> true
    }

    private fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
        return true
    }

    private suspend fun clickText(text: String): Boolean {
        val candidates = rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
        val node = candidates.firstOrNull { it.isVisibleToUser }
        if (node != null && clickNodeOrParent(node)) return true
        return clickTextWithOcr(text)
    }

    private suspend fun clickTextWithOcr(target: String): Boolean {
        val bitmap = captureScreen() ?: return false
        return try {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            recognizer.close()
            val candidate = result.textBlocks.asSequence()
                .flatMap { it.lines.asSequence() }
                .flatMap { line -> line.elements.asSequence() }
                .filter { element -> element.text.equals(target, true) || element.text.contains(target, true) || target.contains(element.text, true) }
                .mapNotNull { it.boundingBox }
                .firstOrNull { bounds ->
                    NodeClickPolicy.isSafeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom, bitmap.width, bitmap.height)
                } ?: return false
            Log.i(TAG, "OCR fallback matched text=$target bounds=$candidate")
            tap(candidate.exactCenterX(), candidate.exactCenterY())
        } catch (error: Throwable) {
            Log.w(TAG, "OCR fallback failed for text=$target", error)
            false
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun captureScreen(): Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val hardwareBuffer = screenshot.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hardwareBuffer.close()
                if (continuation.isActive) continuation.resume(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "Screenshot failed errorCode=$errorCode")
                if (continuation.isActive) continuation.resume(null)
            }
        })
    }

    private suspend fun clickNode(nodeId: Int, observation: Observation): Boolean {
        if (observation.nodes.none { it.id == nodeId }) return false
        val liveNode = findLiveNode(nodeId) ?: return false
        if (!liveNode.isVisibleToUser || !liveNode.isEnabled) return false
        if (clickNodeOrParent(liveNode)) return true

        val bounds = Rect().also(liveNode::getBoundsInScreen)
        if (!NodeClickPolicy.isSafeBounds(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                screenWidth = resources.displayMetrics.widthPixels,
                screenHeight = resources.displayMetrics.heightPixels,
            )) return false
        return tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun findLiveNode(targetId: Int): AccessibilityNodeInfo? {
        val counter = AtomicInteger(1)
        windows.sortedByDescending { it.layer }.forEach { window ->
            window.root?.let { root ->
                if (ObservationPolicy.shouldIncludePackage(root.packageName?.toString())) {
                    findLiveNode(root, targetId, counter, 0)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findLiveNode(
        node: AccessibilityNodeInfo,
        targetId: Int,
        counter: AtomicInteger,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH || counter.get() > MAX_NODES) return null
        if (counter.getAndIncrement() == targetId) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findLiveNode(child, targetId, counter, depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickNodeOrParent(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(5) {
            if (node?.isClickable == true) return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            node = node?.parent
        }
        return false
    }

    private suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val result = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { result.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { result.complete(false) }
        }, null)
        return result.await()
    }

    private fun inputText(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable || focused.isPassword) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private suspend fun swipe(direction: String): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val (startX, startY, endX, endY) = when (direction) {
            "down" -> listOf(width * .5f, height * .3f, width * .5f, height * .75f)
            "left" -> listOf(width * .8f, height * .5f, width * .2f, height * .5f)
            "right" -> listOf(width * .2f, height * .5f, width * .8f, height * .5f)
            else -> listOf(width * .5f, height * .75f, width * .5f, height * .3f)
        }
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        val result = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { result.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { result.complete(false) }
        }, null)
        return result.await()
    }

    companion object {
        private const val TAG = "AndroidAgent"
        private const val MAX_NODES = 250
        private const val MAX_DEPTH = 30
        @Volatile private var instance: AgentAccessibilityService? = null
        fun current(): AgentAccessibilityService? = instance
    }
}
