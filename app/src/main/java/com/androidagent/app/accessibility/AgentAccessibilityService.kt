package com.androidagent.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.androidagent.app.agent.AgentAction
import com.androidagent.app.agent.ActionExecutionResult
import com.androidagent.app.agent.Observation
import com.androidagent.app.agent.UiNodeSnapshot
import com.androidagent.app.agent.ElementMatchPolicy
import com.androidagent.app.agent.ElementSelector
import com.androidagent.app.agent.InputMode
import com.androidagent.app.agent.InputActionResultPolicy
import com.androidagent.app.agent.NodeSelector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
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
        if (AgentController.currentRunId() != null) {
            AgentController.stopWithCause(AgentStopCause.ACCESSIBILITY_INTERRUPTED)
        }
    }

    override fun onDestroy() {
        if (AgentController.currentRunId() != null) {
            AgentController.stopWithCause(AgentStopCause.ACCESSIBILITY_DISCONNECTED)
        }
        if (::overlayController.isInitialized) overlayController.hide()
        serviceScope.cancel()
        if (instance === this) instance = null
        AgentController.setAccessibilityConnected(false)
        super.onDestroy()
    }

    fun observe(): Observation {
        val nodes = mutableListOf<UiNodeSnapshot>()
        val nextId = AtomicInteger(1)
        val currentWindows = windows
        val imeVisible = currentWindows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root != null }
        val observableWindows = currentWindows
            .filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .sortedByDescending { it.layer }
        observableWindows.forEachIndexed { windowIndex, window ->
            window.root?.let { root ->
                if (ObservationPolicy.shouldIncludePackage(root.packageName?.toString())) {
                    collectNodes(root, nodes, nextId, 0, listOf(windowIndex), window.id)
                }
            }
        }
        val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        val imePackages = currentWindows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { it.root?.packageName?.toString() }
            .toSet()
        val foregroundPackage = if (activePackage.isNotBlank() && activePackage !in imePackages) {
            activePackage
        } else {
            observableWindows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.root?.packageName?.toString().orEmpty()
        }
        val windowIds = observableWindows.mapTo(linkedSetOf()) { it.id }
        val windowPackages = observableWindows.mapNotNull { window ->
            window.root?.packageName?.toString()?.takeIf(String::isNotBlank)?.let { window.id to it }
        }.toMap()
        return Observation(
            packageName = foregroundPackage,
            nodes = nodes.take(MAX_NODES),
            imeVisible = imeVisible,
            windowIds = windowIds,
            windowPackages = windowPackages,
        )
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<UiNodeSnapshot>,
        nextId: AtomicInteger,
        depth: Int,
        path: List<Int>,
        windowId: Int,
    ) {
        if (output.size >= MAX_NODES || depth > MAX_DEPTH) return
        val rect = Rect().also(node::getBoundsInScreen)
        val text = if (node.isPassword) "" else node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val className = node.className?.toString().orEmpty()
        val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        val stableKey = stableNodeKey(
            packageName = node.packageName?.toString().orEmpty(),
            windowId = windowId,
            viewId = viewId,
            className = className,
            treePath = path,
        )
        val meaningful = node.isVisibleToUser && (
            node.isClickable || node.isEditable || node.isScrollable || node.isCheckable || node.isSelected ||
                text.isNotBlank() || description.isNotBlank() || viewId.isNotBlank()
            )
        if (meaningful) {
            output += UiNodeSnapshot(
                id = nextId.getAndIncrement(),
                text = text,
                description = description,
                className = className,
                clickable = node.isClickable,
                editable = node.isEditable,
                bounds = bounds,
                stableKey = stableKey,
                viewId = viewId,
                treePath = path,
                enabled = node.isEnabled,
                focused = node.isFocused,
                checked = if (node.isCheckable) node.isChecked else null,
                selected = node.isSelected,
                scrollable = node.isScrollable,
                packageName = node.packageName?.toString().orEmpty(),
                visible = true,
                password = node.isPassword,
                windowId = windowId,
            )
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectNodes(child, output, nextId, depth + 1, path + index, windowId)
            }
        }
    }

    suspend fun execute(action: AgentAction, observation: Observation): Boolean =
        executeDetailed(action, observation).success

    suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult = when (action) {
        is AgentAction.LaunchApp -> result(launchApp(action.packageName), "launched", "launch_app")
        is AgentAction.ClickText -> result(clickText(action.text), "clicked", "click_text")
        is AgentAction.ClickNode -> result(clickNode(action, observation), "clicked", "click_node")
        is AgentAction.TapPoint -> result(tapNormalized(action.x, action.y), "tapped", "tap_point")
        is AgentAction.Swipe -> result(swipe(action.direction), "swiped", "swipe")
        is AgentAction.InputText -> inputTextDetailed(action, observation)
        is AgentAction.SubmitInput -> submitInputDetailed(action, observation)
        is AgentAction.EnsureToggle -> result(ensureToggle(action, observation), "toggle_updated", "ensure_toggle")
        is AgentAction.BindPredicate -> ActionExecutionResult(false, "not_executable", "bind_predicate is runtime-only")
        is AgentAction.Back -> result(performGlobalAction(GLOBAL_ACTION_BACK), "back", "back")
        is AgentAction.Home -> result(performGlobalAction(GLOBAL_ACTION_HOME), "home", "home")
        is AgentAction.Wait -> ActionExecutionResult(true, "waited", "wait")
        is AgentAction.Finish, is AgentAction.Fail -> ActionExecutionResult(true, "terminal", "terminal")
    }

    private fun result(success: Boolean, successStatus: String, detail: String): ActionExecutionResult =
        ActionExecutionResult(success, if (success) successStatus else "execution_failed", detail)

    private fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
        return true
    }

    private suspend fun clickText(text: String): Boolean {
        if (text.isBlank()) return false
        val visible = rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty().filter { it.isVisibleToUser && it.isEnabled }
        val candidates = visible.filterNot { it.isEditable }
        if (visible.isNotEmpty() && candidates.isEmpty()) return false
        val exact = candidates.filter {
            it.text?.toString().equals(text, true) || it.contentDescription?.toString().equals(text, true)
        }
        val node = when {
            exact.size == 1 -> exact.single()
            exact.isEmpty() && candidates.size == 1 -> candidates.single()
            else -> null
        }
        if (node != null && clickNodeOrParent(node)) return true
        if (exact.size > 1 || candidates.size > 1) return false
        return clickTextWithOcr(text)
    }

    private suspend fun clickTextWithOcr(target: String): Boolean {
        if (target.isBlank()) return false
        val bitmap = captureScreen() ?: return false
        return try {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            recognizer.close()
            val imeBounds = findInputMethodWindow()?.let { Rect().also(it::getBoundsInScreen) }
            val elements = result.textBlocks.asSequence()
                .flatMap { it.lines.asSequence() }
                .flatMap { line -> line.elements.asSequence() }
                .filter { element ->
                    val bounds = element.boundingBox
                    bounds == null || imeBounds == null || !imeBounds.contains(bounds.centerX(), bounds.centerY())
                }
                .toList()
            val exact = elements.filter { it.text.equals(target, true) }
            val partial = elements.filter { ImeSubmitPolicy.isSafeOcrPartialMatch(target, it.text) }
            val candidate = (if (exact.isNotEmpty()) exact else partial)
                .mapNotNull { it.boundingBox }
                .filter { bounds ->
                    NodeClickPolicy.isSafeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom, bitmap.width, bitmap.height)
                }
                .singleOrNull() ?: return false
            Log.i(TAG, "OCR fallback matched chars=${target.length} bounds=$candidate")
            tap(candidate.exactCenterX(), candidate.exactCenterY())
        } catch (error: Throwable) {
            Log.w(TAG, "OCR fallback failed chars=${target.length}", error)
            false
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun recognizeScreenText(): String {
        val bitmap = captureScreen() ?: return ""
        return try {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
            } finally {
                recognizer.close()
            }
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun captureScreenDataUrl(observation: Observation? = null): String? {
        val original = captureScreen() ?: return null
        return try {
            val scale = minOf(1f, 1280f / maxOf(original.width, original.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
            } else original
            val bitmap = scaled.copy(Bitmap.Config.ARGB_8888, true)
            try {
                if (observation != null) drawSetOfMarks(bitmap, observation, scale)
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
                "data:image/jpeg;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}"
            } finally {
                bitmap.recycle()
                if (scaled !== original) scaled.recycle()
            }
        } finally {
            original.recycle()
        }
    }

    private fun drawSetOfMarks(bitmap: Bitmap, observation: Observation, scale: Float) {
        val canvas = Canvas(bitmap)
        val redaction = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        observation.nodes.asSequence().filter { node ->
            node.text.contains("[redacted-") || node.description.contains("[redacted-")
        }.forEach { node ->
            val values = node.bounds.split(',').mapNotNull(String::toFloatOrNull)
            if (values.size == 4) {
                canvas.drawRect(values[0] * scale, values[1] * scale, values[2] * scale, values[3] * scale, redaction)
            }
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 72, 72)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 35, 35) }
        observation.nodes.asSequence().filter { it.visible && (it.clickable || it.editable) }.take(100).forEach { node ->
            val values = node.bounds.split(',').mapNotNull(String::toFloatOrNull)
            if (values.size != 4) return@forEach
            val left = values[0] * scale
            val top = values[1] * scale
            val right = values[2] * scale
            val bottom = values[3] * scale
            canvas.drawRect(left, top, right, bottom, stroke)
            val text = node.id.toString()
            val width = label.measureText(text) + 8f
            canvas.drawRect(left, top, left + width, top + 22f, background)
            canvas.drawText(text, left + 4f, top + 17f, label)
        }
    }

    private suspend fun captureScreen(): Bitmap? {
        if (::overlayController.isInitialized) {
            withContext(Dispatchers.Main.immediate) { overlayController.setCaptureHidden(true) }
            delay(50)
        }
        return try {
            captureScreenRaw()
        } finally {
            if (::overlayController.isInitialized) {
                withContext(Dispatchers.Main.immediate) { overlayController.setCaptureHidden(false) }
            }
        }
    }

    private suspend fun captureScreenRaw(): Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
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

    private suspend fun clickNode(action: AgentAction.ClickNode, observation: Observation): Boolean {
        val current = observe()
        if (action.selector == null && current.observationId != observation.observationId) return false
        val snapshot = NodeSelector.resolve(current, action.nodeId, action.selector)
        if (snapshot == null) return false
        val liveNode = findLiveNode(snapshot) ?: return false
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

    private fun findLiveNode(snapshot: UiNodeSnapshot): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestCount = 0
        windows.sortedByDescending { it.layer }.forEach { window ->
            window.root?.let { root ->
                val rootPackage = root.packageName?.toString().orEmpty()
                if (ObservationPolicy.shouldIncludePackage(rootPackage) &&
                    (snapshot.packageName.isBlank() || snapshot.packageName == rootPackage) &&
                    (snapshot.windowId == null || snapshot.windowId == window.id)
                ) {
                    findBestLiveNode(root, snapshot, 0, emptyList()) { node, score ->
                        if (score > bestScore) {
                            bestNode = node
                            bestScore = score
                            bestCount = 1
                        } else if (score == bestScore) {
                            bestCount += 1
                        }
                    }
                }
            }
        }
        val minimumScore = ElementMatchPolicy.minimumScore(snapshot)
        return bestNode?.takeIf { bestScore >= minimumScore && bestCount == 1 }
    }

    private fun findBestLiveNode(
        node: AccessibilityNodeInfo,
        snapshot: UiNodeSnapshot,
        depth: Int,
        path: List<Int>,
        onCandidate: (AccessibilityNodeInfo, Int) -> Unit,
    ) {
        if (depth > MAX_DEPTH) return
        val rect = Rect().also(node::getBoundsInScreen)
        val score = ElementMatchPolicy.score(
            snapshot = snapshot,
            packageName = node.packageName?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            text = node.text?.toString().orEmpty(),
            description = node.contentDescription?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
            editable = node.isEditable,
            clickable = node.isClickable,
            treePath = path,
        )
        if (score > 0) onCandidate(node, score)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findBestLiveNode(child, snapshot, depth + 1, path + index, onCandidate)
        }
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
        if (::overlayController.isInitialized) {
            withContext(Dispatchers.Main.immediate) { overlayController.setCaptureHidden(true) }
            delay(32)
        }
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            val result = CompletableDeferred<Boolean>()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { result.complete(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { result.complete(false) }
            }, null)
            result.await()
        } finally {
            if (::overlayController.isInitialized) {
                withContext(Dispatchers.Main.immediate) { overlayController.setCaptureHidden(false) }
            }
        }
    }

    private suspend fun tapNormalized(x: Int, y: Int): Boolean {
        if (x !in 30..970 || y !in 30..970) return false
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val physicalX = width * (x / 1000f)
        val physicalY = height * (y / 1000f)
        if (!NodeClickPolicy.isSafeBounds(
                physicalX.toInt() - 1,
                physicalY.toInt() - 1,
                physicalX.toInt() + 1,
                physicalY.toInt() + 1,
                width,
                height,
            )) return false
        return tap(physicalX, physicalY)
    }

    private suspend fun inputTextDetailed(action: AgentAction.InputText, observation: Observation): ActionExecutionResult {
        val root = rootInActiveWindow ?: return ActionExecutionResult(false, "target_missing", "no active window")
        val current = observe()
        if (action.nodeId != null && action.target == null && current.observationId != observation.observationId) {
            return ActionExecutionResult(false, "target_missing", "stale numeric input target")
        }
        val focused = if (action.nodeId != null || action.target != null) {
            val snapshot = NodeSelector.resolve(current, action.nodeId, action.target)
                ?: return ActionExecutionResult(false, "target_missing", "input target is missing or ambiguous")
            if (!snapshot.editable || snapshot.password) return ActionExecutionResult(false, "target_missing", "input target is not editable")
            findLiveNode(snapshot) ?: return ActionExecutionResult(false, "target_missing", "live input target is missing")
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable && it.isVisibleToUser && !it.isPassword }
                ?: findUniqueEditableNode(root)
                ?: return ActionExecutionResult(false, "target_missing", "input target is missing or ambiguous")
        }
        if (!focused.isFocused) {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val currentText = focused.text?.toString().orEmpty()
        val nextText = when (action.mode) {
            InputMode.REPLACE -> action.text
            InputMode.APPEND -> currentText + action.text
            InputMode.CLEAR -> ""
        }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, nextText) }
        if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return InputActionResultPolicy.resolve(false, false, action.submit, false)
        }
        delay(180)
        val liveText = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text?.toString()
            ?: focused.text?.toString()
        val verified = liveText == nextText
        if (!verified) return InputActionResultPolicy.resolve(true, false, action.submit, false)
        if (!action.submit) return InputActionResultPolicy.resolve(true, true, false, true)
        val submit = submitInputDetailed(AgentAction.SubmitInput(action.nodeId, action.target), observation)
        return InputActionResultPolicy.resolve(true, true, true, submit.success)
    }

    private suspend fun submitInputDetailed(action: AgentAction.SubmitInput, observation: Observation): ActionExecutionResult {
        val current = observe()
        if (action.nodeId != null && action.target == null && current.observationId != observation.observationId) {
            return ActionExecutionResult(false, "target_missing", "stale numeric submit target")
        }
        val node = if (action.nodeId != null || action.target != null) {
            val snapshot = NodeSelector.resolve(current, action.nodeId, action.target)
                ?: return ActionExecutionResult(false, "target_missing", "submit target is missing or ambiguous")
            if (!snapshot.editable || snapshot.password) return ActionExecutionResult(false, "target_missing", "submit target is not editable")
            findLiveNode(snapshot) ?: return ActionExecutionResult(false, "target_missing", "live submit target is missing")
        } else {
            val root = rootInActiveWindow ?: return ActionExecutionResult(false, "target_missing", "no active window")
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findUniqueEditableNode(root)
                ?: return ActionExecutionResult(false, "target_missing", "submit target is missing or ambiguous")
        }
        if (!node.isEditable || !node.isVisibleToUser) return ActionExecutionResult(false, "target_missing", "submit target is not actionable")
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(80)
        }

        val imeEnterAccepted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (imeEnterAccepted) return ActionExecutionResult(true, "submitted", "IME action accepted")

        val submitKey = findImeSubmitKey()
        if (submitKey != null) {
            if (clickNodeOrParent(submitKey)) return ActionExecutionResult(true, "submitted", "IME submit key clicked")
            val bounds = Rect().also(submitKey::getBoundsInScreen)
            if (NodeClickPolicy.isSafeBounds(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    screenWidth = resources.displayMetrics.widthPixels,
                    screenHeight = resources.displayMetrics.heightPixels,
                )) {
                return if (tap(bounds.exactCenterX(), bounds.exactCenterY())) {
                    ActionExecutionResult(true, "submitted", "IME submit key tapped")
                } else {
                    ActionExecutionResult(false, "submit_failed", "IME submit gesture was cancelled")
                }
            }
        }
        return ActionExecutionResult(false, "submit_failed", "no safe IME submit action was accepted")
    }

    private fun findInputMethodWindow(): AccessibilityWindowInfo? = windows
        .asSequence()
        .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        .maxByOrNull { it.layer }

    private fun findImeSubmitKey(): AccessibilityNodeInfo? {
        val root = findInputMethodWindow()?.root ?: return null
        val candidates = mutableListOf<ImeNodeCandidate>()
        collectImeSubmitCandidates(root, candidates, 0)
        val bestScore = candidates.maxOfOrNull { it.score } ?: return null
        if (bestScore < ImeSubmitPolicy.MINIMUM_SCORE) return null
        val best = candidates.filter { it.score == bestScore }
        val distinctBounds = best.map { it.bounds }.distinct()
        if (distinctBounds.size != 1) {
            Log.w(TAG, "IME submit key is ambiguous: ${best.size} candidates at score=$bestScore")
            return null
        }
        return best.firstOrNull { it.node.isClickable }?.node ?: best.first().node
    }

    private fun collectImeSubmitCandidates(
        node: AccessibilityNodeInfo,
        output: MutableList<ImeNodeCandidate>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val score = ImeSubmitPolicy.score(
            viewId = node.viewIdResourceName.orEmpty(),
            text = node.text?.toString().orEmpty(),
            description = node.contentDescription?.toString().orEmpty(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
        )
        if (score > 0) {
            val bounds = Rect().also(node::getBoundsInScreen)
            output += ImeNodeCandidate(node, score, "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectImeSubmitCandidates(child, output, depth + 1)
        }
    }

    private suspend fun ensureToggle(action: AgentAction.EnsureToggle, observation: Observation): Boolean {
        val current = observe()
        if (action.selector == null && current.observationId != observation.observationId) return false
        val snapshot = NodeSelector.resolve(current, action.nodeId, action.selector) ?: return false
        return when (val state = ToggleStatePolicy.state(snapshot)) {
            ToggleState.ON -> if (action.desired) true else clickNode(AgentAction.ClickNode(action.nodeId, action.selector), observation)
            ToggleState.OFF -> if (action.desired) clickNode(AgentAction.ClickNode(action.nodeId, action.selector), observation) else true
            ToggleState.UNKNOWN -> false
        }
    }

    private fun findUniqueEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, matches, 0)
        return matches.singleOrNull()
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > MAX_DEPTH || output.size > 1) return
        if (node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword) output += node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectEditableNodes(child, output, depth + 1)
        }
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

        /** Stable structure-only key; mutable text, state, and bounds are intentionally excluded. */
        internal fun stableNodeKey(
            packageName: String,
            windowId: Int,
            viewId: String,
            className: String,
            treePath: List<Int>,
        ): String = MessageDigest.getInstance("SHA-256")
            .digest(listOf(packageName, windowId.toString(), viewId, className, treePath.joinToString("/")).joinToString("|").toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

private data class ImeNodeCandidate(
    val node: AccessibilityNodeInfo,
    val score: Int,
    val bounds: String,
)

internal object ImeSubmitPolicy {
    const val MINIMUM_SCORE = 60

    private val exactLabels = setOf("go", "done", "send", "next", "ok", "confirm")
    private val positiveIdTokens = setOf("ime_action", "action_key", "done", "send", "next", "confirm", "key_go", "key_ok")
    private val negativeIdTokens = setOf(
        "delete", "backspace", "space", "shift", "voice", "symbol", "number", "digit",
        "candidate", "suggestion", "keyboard_switch", "enter", "return", "newline", "linefeed",
    )

    fun score(
        viewId: String,
        text: String,
        description: String,
        clickable: Boolean,
        enabled: Boolean,
        visible: Boolean,
    ): Int {
        if (!enabled || !visible) return 0
        val id = viewId.lowercase()
        if (negativeIdTokens.any(id::contains)) return 0
        val labels = sequenceOf(text, description).map(::normalizeLabel).filter(String::isNotBlank).toList()
        if (labels.any(::isSingleInputKey)) return 0
        var score = 0
        if (labels.any(exactLabels::contains)) score += 70
        if (positiveIdTokens.any(id::contains)) score += 65
        if (clickable) score += 10
        return score
    }

    fun isSafeOcrPartialMatch(target: String, recognized: String): Boolean {
        val normalizedTarget = normalizeLabel(target)
        val normalizedRecognized = normalizeLabel(recognized)
        return normalizedTarget.length >= 2 && normalizedRecognized.contains(normalizedTarget, ignoreCase = true)
    }

    private fun normalizeLabel(value: String): String = value.trim().lowercase().replace(Regex("[\\s:,.!?]+"), "")
    private fun isSingleInputKey(value: String): Boolean =
        value.length == 1 && (value.single().isLetterOrDigit() || value.single() in ",.!?+-*/")
}

internal enum class ToggleState { ON, OFF, UNKNOWN }

internal object ToggleStatePolicy {
    fun state(snapshot: UiNodeSnapshot): ToggleState {
        if (snapshot.checked == true || snapshot.selected) return ToggleState.ON
        if (snapshot.checked == false) return ToggleState.OFF
        val className = snapshot.className.lowercase()
        val viewId = snapshot.viewId.lowercase()
        val genericToggle = listOf("switch", "checkbox", "radiobutton", "toggle").any {
            className.contains(it) || viewId.contains(it)
        }
        return if (genericToggle) ToggleState.OFF else ToggleState.UNKNOWN
    }
}
