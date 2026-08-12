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
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.androidagent.app.agent.AgentAction
import com.androidagent.app.agent.ActionDispatchMode
import com.androidagent.app.agent.ActionExecutionResult
import com.androidagent.app.agent.Observation
import com.androidagent.app.agent.UiNodeSnapshot
import com.androidagent.app.agent.InputMode
import com.androidagent.app.agent.InputActionResultPolicy
import com.androidagent.app.agent.NodeIdentityKeys
import com.androidagent.app.agent.ResolvedActionTarget
import com.androidagent.app.overlay.AgentOverlayController
import com.androidagent.app.privileged.PrivilegedDeviceBackend
import com.androidagent.app.privileged.PrivilegedBackendRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class AgentAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlayController: AgentOverlayController

    // Building a recognizer loads the Chinese model; doing that per OCR call cost
    // hundreds of milliseconds on the observation path.
    private val textRecognizerDelegate = lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val textRecognizer by textRecognizerDelegate

    /**
     * takeScreenshot is rate limited by the framework to roughly one call per
     * second; a capture inside that window fails with
     * ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT. Vision planning, OCR, and the
     * before/after critic captures routinely land closer together than that, so
     * requests are spaced instead of being allowed to fail silently.
     */
    private val screenshotMutex = Mutex()

    @Volatile
    private var lastScreenshotAtElapsedRealtime = 0L

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

    /**
     * The framework calls this to tell a service to stop *announcing* feedback, not
     * to tear the service down. The binding stays alive and every node and gesture
     * API keeps working, so cancelling the run here aborted healthy long tasks for
     * no reason. Real teardown arrives through [onDestroy].
     */
    override fun onInterrupt() {
        Log.w(TAG, "Accessibility feedback interrupted; the run continues")
    }

    override fun onDestroy() {
        if (AgentController.currentRunId() != null) {
            AgentController.stopWithCause(AgentStopCause.ACCESSIBILITY_DISCONNECTED)
        }
        if (::overlayController.isInitialized) overlayController.hide()
        serviceScope.cancel()
        if (textRecognizerDelegate.isInitialized()) textRecognizer.close()
        if (instance === this) instance = null
        AgentController.setAccessibilityConnected(false)
        super.onDestroy()
    }

    fun observe(): Observation {
        val nodes = mutableListOf<UiNodeSnapshot>()
        val nextId = AtomicInteger(1)
        val collectionState = ObservationCollectionState(MAX_NODES, MAX_DEPTH)
        val currentWindows = windows
        val imeVisible = currentWindows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root != null }
        val observableWindows = currentWindows
            .filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .sortedByDescending { it.layer }
        observableWindows.forEachIndexed { windowIndex, window ->
            val root = window.root
            if (root == null) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    collectionState.recordMissingApplicationRoot()
                }
            } else if (ObservationPolicy.shouldIncludePackage(root.packageName?.toString())) {
                collectNodes(root, nodes, nextId, 0, listOf(windowIndex), window.id, collectionState)
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
            nodes = nodes,
            imeVisible = imeVisible,
            windowIds = windowIds,
            windowPackages = windowPackages,
            isComplete = collectionState.isComplete,
            collectionIssues = collectionState.issueSummary(),
        )
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<UiNodeSnapshot>,
        nextId: AtomicInteger,
        depth: Int,
        path: List<Int>,
        windowId: Int,
        collectionState: ObservationCollectionState,
    ) {
        if (collectionState.shouldStopAtNodeCount(output.size) || collectionState.shouldStopAtDepth(depth)) return
        val rect = Rect().also(node::getBoundsInScreen)
        val text = if (node.isPassword) "" else node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val className = node.className?.toString().orEmpty()
        val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        val withinWindowStableKey = stableNodeKey(
            packageName = node.packageName?.toString().orEmpty(),
            windowId = windowId,
            viewId = viewId,
            className = className,
            treePath = path,
        )
        val crossWindowStructureKey = crossWindowStructureKey(
            packageName = node.packageName?.toString().orEmpty(),
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
                withinWindowStableKey = withinWindowStableKey,
                crossWindowStructureKey = crossWindowStructureKey,
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
            collectionState.recordNodeCount(output.size)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index)
            if (child == null) {
                collectionState.recordMissingChild()
            } else {
                collectNodes(child, output, nextId, depth + 1, path + index, windowId, collectionState)
            }
        }
    }

    suspend fun execute(action: AgentAction, observation: Observation): Boolean =
        executeDetailed(action, observation).success

    suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult = when (action) {
        is AgentAction.ClickText,
        is AgentAction.ClickNode,
        is AgentAction.InputText,
        is AgentAction.SubmitInput,
        is AgentAction.EnsureToggle,
        -> ActionExecutionResult(false, "target_resolution_required", "runtime must supply the canonical resolved target")
        else -> executeDetailed(action, observation, null)
    }

    suspend fun executeDetailed(
        action: AgentAction,
        observation: Observation,
        resolvedTarget: ResolvedActionTarget?,
    ): ActionExecutionResult = when (action) {
        is AgentAction.LaunchApp -> result(launchApp(action.packageName), "launched", "launch_app")
        is AgentAction.ClickText -> result(clickResolvedTarget(resolvedTarget), "clicked", "click_text")
        is AgentAction.ClickNode -> result(clickResolvedTarget(resolvedTarget), "clicked", "click_node")
        is AgentAction.TapPoint -> result(tapNormalized(action.x, action.y), "tapped", "tap_point")
        is AgentAction.Swipe -> result(swipe(action.direction), "swiped", "swipe")
        is AgentAction.InputText -> inputTextDetailed(action, resolvedTarget)
        is AgentAction.SubmitInput -> submitInputDetailed(resolvedTarget)
        is AgentAction.EnsureToggle -> result(ensureToggle(action, resolvedTarget), "toggle_updated", "ensure_toggle")
        is AgentAction.BindPredicate -> ActionExecutionResult(false, "not_executable", "bind_predicate is runtime-only")
        is AgentAction.Terminal -> executeTerminal(action)
        is AgentAction.Back -> result(globalActionOrPrivileged(GLOBAL_ACTION_BACK, KeyEvent.KEYCODE_BACK), "back", "back")
        is AgentAction.Home -> result(globalActionOrPrivileged(GLOBAL_ACTION_HOME, KeyEvent.KEYCODE_HOME), "home", "home")
        is AgentAction.Wait -> ActionExecutionResult(true, "waited", "wait")
        is AgentAction.Finish, is AgentAction.Fail -> ActionExecutionResult(true, "terminal", "terminal")
    }

    private fun result(success: Boolean, successStatus: String, detail: String): ActionExecutionResult =
        ActionExecutionResult(success, if (success) successStatus else "execution_failed", detail)

    private suspend fun executeTerminal(action: AgentAction.Terminal): ActionExecutionResult {
        if (!PrivilegedBackendRouter.isReady()) {
            return ActionExecutionResult(false, "terminal_unavailable", "Shizuku terminal is not connected")
        }
        val commandResult = PrivilegedBackendRouter.execute(action.command, action.timeoutMillis)
        val detail = buildString {
            append("exitCode=${commandResult.exitCode}")
            if (commandResult.stdout.isNotBlank()) append("\nstdout:\n").append(commandResult.stdout.take(8_000))
            if (commandResult.stderr.isNotBlank()) append("\nstderr:\n").append(commandResult.stderr.take(4_000))
        }
        return ActionExecutionResult(
            success = commandResult.success,
            status = if (commandResult.success) "terminal_completed" else "terminal_failed",
            detail = detail,
        )
    }

    private suspend fun launchApp(packageName: String): Boolean {
        if (PrivilegedDeviceBackend.launchPackage(packageName)) return true
        val standardLaunch = runCatching {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            true
        }.getOrDefault(false)
        return standardLaunch
    }

    suspend fun recognizeScreenText(): String {
        val bitmap = captureScreen() ?: return ""
        return try {
            textRecognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
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

    private suspend fun captureScreen(): Bitmap? = screenshotMutex.withLock {
        val sinceLast = SystemClock.elapsedRealtime() - lastScreenshotAtElapsedRealtime
        if (sinceLast in 0 until SCREENSHOT_MIN_INTERVAL_MS) delay(SCREENSHOT_MIN_INTERVAL_MS - sinceLast)
        if (::overlayController.isInitialized) {
            withContext(Dispatchers.Main.immediate) { overlayController.setCaptureHidden(true) }
            delay(50)
        }
        return@withLock try {
            var bitmap = captureScreenRaw()
            if (bitmap == null) {
                // A throttled request is the common failure and it succeeds on a
                // second try once the framework interval has elapsed.
                delay(SCREENSHOT_MIN_INTERVAL_MS)
                bitmap = captureScreenRaw()
            }
            bitmap
        } finally {
            lastScreenshotAtElapsedRealtime = SystemClock.elapsedRealtime()
            if (::overlayController.isInitialized) {
                withContext(Dispatchers.Main.immediate) { overlayController.setCaptureHidden(false) }
            }
        }
    }

    private suspend fun captureScreenRaw(): Bitmap? = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()
                    if (continuation.isActive) {
                        continuation.resume(bitmap)
                    } else {
                        bitmap?.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Screenshot failed errorCode=$errorCode")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }.also { if (it == null) Log.w(TAG, "Screenshot timed out after ${SCREENSHOT_TIMEOUT_MS}ms") }

    private suspend fun clickResolvedTarget(resolvedTarget: ResolvedActionTarget?): Boolean {
        if (resolvedTarget?.dispatchMode != ActionDispatchMode.ACCESSIBILITY_CLICK) return false
        val snapshot = resolvedTarget?.effectiveActionNode ?: return false
        val liveNode = findLiveNode(snapshot) ?: return false
        if (!liveNode.isVisibleToUser || !liveNode.isEnabled || !liveNode.isClickable) return false
        val bounds = Rect().also(liveNode::getBoundsInScreen)
        if (!NodeClickPolicy.isSafeBounds(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                screenWidth = resources.displayMetrics.widthPixels,
                screenHeight = resources.displayMetrics.heightPixels,
            )) return liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return tap(bounds.exactCenterX(), bounds.exactCenterY()) {
            liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun findLiveNode(snapshot: UiNodeSnapshot): AccessibilityNodeInfo? {
        findLiveNodeByTreePath(snapshot)?.let { return it }
        return findLiveNodeByIdentity(snapshot)
    }

    private fun findLiveNodeByTreePath(snapshot: UiNodeSnapshot): AccessibilityNodeInfo? {
        val treePath = snapshot.treePath ?: return null
        val matchingWindows = windows.filter { window ->
            snapshot.windowId == null || snapshot.windowId == window.id
        }
        if (matchingWindows.size != 1) return null
        var candidate = matchingWindows.single().root ?: return null
        for (childIndex in treePath.drop(1)) {
            candidate = candidate.getChild(childIndex) ?: return null
        }
        return candidate.takeIf { liveNodeMatchesSnapshot(it, snapshot) }
    }

    private fun findLiveNodeByIdentity(snapshot: UiNodeSnapshot): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        windows.sortedByDescending { it.layer }.forEach { window ->
            window.root?.let { root ->
                val rootPackage = root.packageName?.toString().orEmpty()
                if (ObservationPolicy.shouldIncludePackage(rootPackage) &&
                    (snapshot.packageName.isBlank() || snapshot.packageName == rootPackage)
                ) {
                    collectLiveIdentityMatches(root, snapshot, 0, matches)
                }
            }
        }
        return matches.singleOrNull()
    }

    private fun liveNodeMatchesSnapshot(node: AccessibilityNodeInfo, snapshot: UiNodeSnapshot): Boolean {
        if (snapshot.packageName.isNotBlank() && node.packageName?.toString() != snapshot.packageName) {
            return false
        }
        if (snapshot.className.isNotBlank() && node.className?.toString() != snapshot.className) {
            return false
        }
        if (snapshot.viewId.isNotBlank() && node.viewIdResourceName != snapshot.viewId) {
            return false
        }
        return true
    }

    private fun collectLiveIdentityMatches(
        node: AccessibilityNodeInfo,
        snapshot: UiNodeSnapshot,
        depth: Int,
        output: MutableList<AccessibilityNodeInfo>,
    ) {
        if (depth > MAX_DEPTH || output.size > 1) return
        val rect = Rect().also(node::getBoundsInScreen)
        val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        val text = if (node.isPassword) "" else node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val strongIdentityMatches = liveNodeMatchesSnapshot(node, snapshot) && when {
            snapshot.viewId.isNotBlank() -> node.viewIdResourceName == snapshot.viewId
            snapshot.text.isNotBlank() && text == snapshot.text -> true
            snapshot.description.isNotBlank() && description == snapshot.description -> true
            snapshot.bounds.isNotBlank() -> bounds == snapshot.bounds
            else -> false
        }
        if (strongIdentityMatches) output += node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectLiveIdentityMatches(child, snapshot, depth + 1, output)
        }
    }

    private fun clickImeNodeOrParent(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(5) {
            if (node?.isClickable == true) return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            node = node?.parent
        }
        return false
    }

    private suspend fun tap(x: Float, y: Float, accessibilityClick: (() -> Boolean)? = null): Boolean {
        if (::overlayController.isInitialized) {
            withContext(Dispatchers.Main.immediate) { overlayController.setCaptureHidden(true) }
            delay(32)
        }
        return try {
            // Prefer the live accessibility node. input tap reports success even
            // when it hits the progress overlay or a transformed coordinate.
            if (accessibilityClick?.invoke() == true) return true
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            if (dispatchGestureAwait(gesture, GESTURE_TIMEOUT_MS)) return true
            PrivilegedDeviceBackend.tap(x.toInt(), y.toInt())
        } finally {
            if (::overlayController.isInitialized) {
                withContext(Dispatchers.Main.immediate) { overlayController.setCaptureHidden(false) }
            }
        }
    }

    /**
     * Gesture callbacks can stall on some OEMs when the display sleeps or the
     * window hierarchy is mid-transition. Never await forever — fall through to
     * privileged backends or report failure so the agent loop stays live.
     */
    private suspend fun dispatchGestureAwait(
        gesture: GestureDescription,
        timeoutMs: Long,
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.complete(false)
            }
        }, null)
        if (!dispatched) return false
        return try {
            withTimeout(timeoutMs) { result.await() }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Gesture callback timed out after ${timeoutMs}ms")
            false
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

    private suspend fun inputTextDetailed(
        action: AgentAction.InputText,
        resolvedTarget: ResolvedActionTarget?,
    ): ActionExecutionResult {
        if (resolvedTarget?.dispatchMode !in setOf(ActionDispatchMode.SET_TEXT, ActionDispatchMode.SET_TEXT_AND_SUBMIT)) {
            return ActionExecutionResult(false, "target_missing", "input dispatch mode is invalid")
        }
        val snapshot = resolvedTarget?.effectiveActionNode
            ?: return ActionExecutionResult(false, "target_missing", "input target was not resolved before dispatch")
        if (!snapshot.editable || snapshot.password) {
            return ActionExecutionResult(false, "target_missing", "input target is not editable")
        }
        val focused = findLiveNode(snapshot)
            ?: return ActionExecutionResult(false, "target_missing", "live input target is missing")
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
        focused.refresh()
        val liveText = focused.text?.toString()
        val verified = liveText == nextText
        if (!verified) return InputActionResultPolicy.resolve(true, false, action.submit, false)
        if (!action.submit) return InputActionResultPolicy.resolve(true, true, false, true)
        val submit = submitInputDetailed(resolvedTarget)
        return InputActionResultPolicy.resolve(true, true, true, submit.success)
    }

    private suspend fun submitInputDetailed(resolvedTarget: ResolvedActionTarget?): ActionExecutionResult {
        if (resolvedTarget?.dispatchMode !in setOf(ActionDispatchMode.SUBMIT_INPUT, ActionDispatchMode.SET_TEXT_AND_SUBMIT)) {
            return ActionExecutionResult(false, "target_missing", "submit dispatch mode is invalid")
        }
        val snapshot = resolvedTarget?.effectiveActionNode
            ?: return ActionExecutionResult(false, "target_missing", "submit target was not resolved before dispatch")
        if (!snapshot.editable || snapshot.password) {
            return ActionExecutionResult(false, "target_missing", "submit target is not editable")
        }
        val node = findLiveNode(snapshot)
            ?: return ActionExecutionResult(false, "target_missing", "live submit target is missing")
        if (!node.isEditable || !node.isVisibleToUser) return ActionExecutionResult(false, "target_missing", "submit target is not actionable")
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(80)
        }

        if (PrivilegedDeviceBackend.keyEvent(KeyEvent.KEYCODE_ENTER)) {
            return ActionExecutionResult(true, "submitted", "privileged Enter key accepted")
        }

        val imeEnterAccepted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (imeEnterAccepted) return ActionExecutionResult(true, "submitted", "IME action accepted")

        val submitKey = findImeSubmitKey()
        if (submitKey != null) {
            if (clickImeNodeOrParent(submitKey)) return ActionExecutionResult(true, "submitted", "IME submit key clicked")
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

    private suspend fun ensureToggle(action: AgentAction.EnsureToggle, resolvedTarget: ResolvedActionTarget?): Boolean {
        val snapshot = resolvedTarget?.semanticNode ?: return false
        return when (ToggleStatePolicy.state(snapshot)) {
            ToggleState.ON -> if (action.desired) true else clickResolvedTarget(resolvedTarget)
            ToggleState.OFF -> if (action.desired) clickResolvedTarget(resolvedTarget) else true
            ToggleState.UNKNOWN -> false
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
        if (PrivilegedDeviceBackend.swipe(
                startX.toInt(),
                startY.toInt(),
                endX.toInt(),
                endY.toInt(),
                450,
            )) return true
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        return dispatchGestureAwait(gesture, SWIPE_TIMEOUT_MS)
    }

    private suspend fun globalActionOrPrivileged(globalAction: Int, keyCode: Int): Boolean =
        PrivilegedDeviceBackend.keyEvent(keyCode) || performGlobalAction(globalAction)

    companion object {
        private const val TAG = "AndroidAgent"
        private const val MAX_NODES = 250
        private const val MAX_DEPTH = 30
        private const val SCREENSHOT_MIN_INTERVAL_MS = 1_000L
        private const val SCREENSHOT_TIMEOUT_MS = 4_000L
        private const val GESTURE_TIMEOUT_MS = 2_500L
        private const val SWIPE_TIMEOUT_MS = 3_500L
        @Volatile private var instance: AgentAccessibilityService? = null
        fun current(): AgentAccessibilityService? = instance

        /** Stable structure-only key; mutable text, state, and bounds are intentionally excluded. */
        internal fun stableNodeKey(
            packageName: String,
            windowId: Int,
            viewId: String,
            className: String,
            treePath: List<Int>,
        ): String = NodeIdentityKeys.withinWindowStableKey(packageName, windowId, viewId, className, treePath)

        /** Stable structure key that deliberately excludes window identity. */
        internal fun crossWindowStructureKey(
            packageName: String,
            viewId: String,
            className: String,
            treePath: List<Int>,
        ): String = NodeIdentityKeys.crossWindowStructureKey(packageName, viewId, className, treePath)
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
