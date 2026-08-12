package com.androidagent.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidagent.app.accessibility.AgentAccessibilityService
import com.androidagent.app.accessibility.AgentController
import com.androidagent.app.accessibility.AgentStartResult
import com.androidagent.app.agent.RuntimeOutcome
import com.androidagent.app.chat.ChatMessage
import com.androidagent.app.chat.ChatStore
import com.androidagent.app.chat.Conversation
import com.androidagent.app.data.PersonalizationStore
import com.androidagent.app.data.MuseThemeMode
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.network.TerminalAgentClient
import com.androidagent.app.network.TerminalCommandPolicy
import com.androidagent.app.privileged.PrivilegedBackendRouter
import com.androidagent.app.privileged.ShizukuBridge
import com.androidagent.app.terminal.TERMINAL_TOOLS
import com.androidagent.app.terminal.EmbeddedLinuxEnvironment
import com.androidagent.app.terminal.EnvironmentInstallProgress
import com.androidagent.app.terminal.InstalledLinuxEnvironment
import com.androidagent.app.terminal.TerminalEnvironmentConfig
import com.androidagent.app.terminal.TerminalEnvironmentProbe
import com.androidagent.app.update.DownloadProgress
import com.androidagent.app.update.GitHubUpdater
import com.androidagent.app.update.InstallerLaunchResult
import com.androidagent.app.update.UpdateInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private data class MusePalette(
    val background: Color,
    val surfaceLow: Color,
    val surfaceHigh: Color,
    val surfaceRaised: Color,
    val primary: Color,
    val accent: Color,
    val tertiary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val warning: Color,
    val success: Color,
    val teal: Color,
    val error: Color,
    val onAccent: Color,
)

// Official Catppuccin Mocha. Dark stays faithful so the product still reads as Mocha.
private val MochaPalette = MusePalette(
    background = Color(0xFF1E1E2E),
    surfaceLow = Color(0xFF181825),
    surfaceHigh = Color(0xFF11111B),
    surfaceRaised = Color(0xFF313244),
    primary = Color(0xFF89B4FA),
    accent = Color(0xFFCBA6F7),
    tertiary = Color(0xFFFAB387),
    textPrimary = Color(0xFFCDD6F4),
    textSecondary = Color(0xFFA6ADC8),
    divider = Color(0xFF45475A),
    warning = Color(0xFFF9E2AF),
    success = Color(0xFFA6E3A1),
    teal = Color(0xFF94E2D5),
    error = Color(0xFFF38BA8),
    onAccent = Color(0xFF11111B),
)

// Catppuccin Latte, sun-warmed: same rosewater / peach / mauve family,
// but cream paper instead of the official cool blue-gray base.
private val LattePalette = MusePalette(
    background = Color(0xFFF6F0E6),
    surfaceLow = Color(0xFFFFF9F2),
    surfaceHigh = Color(0xFFEFE4D6),
    surfaceRaised = Color(0xFFE7D8C8),
    primary = Color(0xFFC96B4A),
    accent = Color(0xFFA36BB5),
    tertiary = Color(0xFFD98A3D),
    textPrimary = Color(0xFF433D38),
    textSecondary = Color(0xFF7A7066),
    divider = Color(0xFFD9CBBA),
    warning = Color(0xFFDF8E1D),
    success = Color(0xFF4F8F4A),
    teal = Color(0xFF2E8A80),
    error = Color(0xFFC94A5A),
    onAccent = Color(0xFFFFF8F1),
)

private val LocalMusePalette = staticCompositionLocalOf { MochaPalette }
private val Void: Color @Composable get() = LocalMusePalette.current.background
private val SurfaceLow: Color @Composable get() = LocalMusePalette.current.surfaceLow
private val SurfaceHigh: Color @Composable get() = LocalMusePalette.current.surfaceHigh
private val SurfaceRaised: Color @Composable get() = LocalMusePalette.current.surfaceRaised
private val NeonCyan: Color @Composable get() = LocalMusePalette.current.primary
private val NeonPink: Color @Composable get() = LocalMusePalette.current.accent
private val AcidYellow: Color @Composable get() = LocalMusePalette.current.tertiary
private val TextPrimary: Color @Composable get() = LocalMusePalette.current.textPrimary
private val TextSecondary: Color @Composable get() = LocalMusePalette.current.textSecondary
private val Divider: Color @Composable get() = LocalMusePalette.current.divider
private val Warning: Color @Composable get() = LocalMusePalette.current.warning
private val Success: Color @Composable get() = LocalMusePalette.current.success
private val Teal: Color @Composable get() = LocalMusePalette.current.teal
private val Error: Color @Composable get() = LocalMusePalette.current.error
private val OnAccent: Color @Composable get() = LocalMusePalette.current.onAccent
private val CyberShape = RoundedCornerShape(18.dp)
private val CompactShape = RoundedCornerShape(12.dp)

@Composable
private fun MuseTheme(mode: MuseThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        MuseThemeMode.SYSTEM -> isSystemInDarkTheme()
        MuseThemeMode.LIGHT -> false
        MuseThemeMode.DARK -> true
    }
    val target = if (dark) MochaPalette else LattePalette
    @Composable
    fun animated(color: Color, label: String): Color = animateColorAsState(
        targetValue = color,
        animationSpec = tween(320),
        label = label,
    ).value
    val palette = MusePalette(
        background = animated(target.background, "theme-background"),
        surfaceLow = animated(target.surfaceLow, "theme-surface-low"),
        surfaceHigh = animated(target.surfaceHigh, "theme-surface-high"),
        surfaceRaised = animated(target.surfaceRaised, "theme-surface-raised"),
        primary = animated(target.primary, "theme-primary"),
        accent = animated(target.accent, "theme-accent"),
        tertiary = animated(target.tertiary, "theme-tertiary"),
        textPrimary = animated(target.textPrimary, "theme-text-primary"),
        textSecondary = animated(target.textSecondary, "theme-text-secondary"),
        divider = animated(target.divider, "theme-divider"),
        warning = animated(target.warning, "theme-warning"),
        success = animated(target.success, "theme-success"),
        teal = animated(target.teal, "theme-teal"),
        error = animated(target.error, "theme-error"),
        onAccent = animated(target.onAccent, "theme-on-accent"),
    )
    val scheme = if (dark) darkColorScheme(
        primary = palette.primary, secondary = palette.accent, tertiary = palette.tertiary,
        background = palette.background, surface = palette.surfaceLow, surfaceVariant = palette.surfaceRaised,
        onBackground = palette.textPrimary, onSurface = palette.textPrimary, onPrimary = palette.onAccent,
        onSecondary = palette.onAccent, error = palette.error,
    ) else lightColorScheme(
        primary = palette.primary, secondary = palette.accent, tertiary = palette.tertiary,
        background = palette.background, surface = palette.surfaceLow, surfaceVariant = palette.surfaceRaised,
        onBackground = palette.textPrimary, onSurface = palette.textPrimary, onPrimary = palette.onAccent,
        onSecondary = palette.onAccent, error = palette.error,
    )
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = palette.surfaceHigh.toArgb()
        window.navigationBarColor = palette.surfaceHigh.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
    }
    CompositionLocalProvider(LocalMusePalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuBridge.initialize(applicationContext)
        setContent {
            val settings = remember { SecureSettings(applicationContext) }
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            MuseTheme(themeMode) {
                MuseApp(
                    settings = settings,
                    themeMode = themeMode,
                    onThemeModeChange = { selected ->
                        settings.themeMode = selected
                        themeMode = selected
                    },
                )
            }
        }
    }
}

private enum class MusePage(val label: String) {
    Chat("Chat"),
    Configure("Configure"),
    Personal("Personalization"),
}

@Composable
private fun CatppuccinBackdrop() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Void,
                        SurfaceHigh.copy(alpha = 0.55f),
                        Void,
                    ),
                ),
            ),
    )
}

@Composable
private fun MuseApp(
    settings: SecureSettings,
    themeMode: MuseThemeMode,
    onThemeModeChange: (MuseThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val memoryStore = remember { PersonalizationStore(context) }
    val chatStore = remember { ChatStore(context) }
    val scope = rememberCoroutineScope()
    val shizukuState by ShizukuBridge.state.collectAsState()
    val agentState by AgentController.state.collectAsState()

    var page by remember { mutableStateOf(MusePage.Chat) }
    var conversations by remember {
        mutableStateOf(chatStore.load().ifEmpty { listOf(Conversation(title = "新对话")) })
    }
    var selectedId by remember { mutableStateOf(conversations.first().id) }
    var input by remember { mutableStateOf("") }
    var runStatus by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var environmentStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val updater = remember { GitHubUpdater(context.applicationContext) }
    var autoUpdateEnabled by remember { mutableStateOf(settings.autoUpdateEnabled) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf(if (autoUpdateEnabled) "Auto update ready" else "Auto update off") }
    var checkingUpdate by remember { mutableStateOf(false) }
    var installingUpdate by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf<DownloadProgress?>(null) }

    fun checkForUpdates(manual: Boolean) {
        if (checkingUpdate || installingUpdate) return
        checkingUpdate = true
        updateStatus = if (manual) "正在检查 GitHub Release" else "启动自动检查"
        scope.launch {
            runCatching { updater.check(settings.githubRepository) }
                .onSuccess { update ->
                    availableUpdate = update
                    updateStatus = if (update == null) "当前已是最新版本" else "发现 v${update.version} 更新"
                }
                .onFailure { error ->
                    updateStatus = "检查失败：${error.message ?: error::class.java.simpleName}"
                }
            checkingUpdate = false
        }
    }

    fun installAvailableUpdate() {
        val update = availableUpdate ?: return
        if (installingUpdate || checkingUpdate) return
        installingUpdate = true
        updateProgress = null
        updateStatus = "正在下载并校验 v${update.version}"
        scope.launch {
            runCatching {
                updater.downloadAndInstall(update) { progress -> updateProgress = progress }
            }.onSuccess { result ->
                updateStatus = when (result) {
                    InstallerLaunchResult.INSTALLER_OPENED -> "系统安装器已打开，请确认覆盖安装"
                    InstallerLaunchResult.PERMISSION_REQUIRED -> "APK 已校验，请允许安装未知应用后再次点击"
                }
            }.onFailure { error ->
                updateStatus = "更新失败：${error.message ?: error::class.java.simpleName}"
                updateProgress = null
            }
            installingUpdate = false
        }
    }

    LaunchedEffect(Unit) {
        PrivilegedBackendRouter.configure(context, true)
        if (autoUpdateEnabled) checkForUpdates(manual = false)
    }

    LaunchedEffect(shizukuState.connected) {
        if (shizukuState.connected) {
            environmentStatus = TerminalEnvironmentProbe.probe(TerminalEnvironmentConfig.from(settings))
        } else {
            environmentStatus = emptyMap()
        }
    }

    fun persist(updated: List<Conversation>) {
        conversations = updated
        chatStore.save(updated)
    }

    fun updateConversation(conversation: Conversation) {
        persist(conversations.map { if (it.id == conversation.id) conversation else it })
    }

    suspend fun terminalAgentReply(question: String, conversation: Conversation): String =
        TerminalAgentClient().respond(
            apiKey = settings.apiKey,
            baseUrl = settings.modelBaseUrl,
            model = settings.modelName,
            provider = settings.currentProvider,
            input = question,
            history = conversation.messages.map { it.role to it.content },
            memoryMarkdown = memoryStore.loadMemory(),
            contextLength = settings.contextLength,
            maxOutputTokens = settings.maxOutputTokens,
            environment = TerminalEnvironmentConfig.from(settings),
            environmentStatus = environmentStatus,
            terminalAvailable = shizukuState.connected,
            execute = PrivilegedBackendRouter::execute,
            onProgress = { progress -> runStatus = progress },
        )

    fun sendMessage() {
        val text = input.trim()
        if (text.isBlank() || activeJob?.isActive == true || agentState.running) return
        val current = conversations.firstOrNull { it.id == selectedId } ?: return
        val runConversationId = current.id
        val withUser = current.copy(
            title = if (current.messages.isEmpty()) text.take(24) else current.title,
            updatedAt = System.currentTimeMillis(),
            messages = current.messages + ChatMessage("user", text),
        )
        updateConversation(withUser)
        input = ""
        runStatus = "正在思考"
        activeJob = scope.launch {
            val progressMirror = launch {
                AgentController.state.collectLatest { state ->
                    if (!state.running && state.progressSummaries.isEmpty()) return@collectLatest
                    val summary = state.progressSummaries.lastOrNull()
                        ?: state.currentAction.takeIf { it.isNotBlank() }
                        ?: state.status
                    runStatus = "${state.step}/${state.maxSteps} · $summary"
                }
            }
            val reply = runCatching {
                when {
                    text.startsWith("/shell ", ignoreCase = true) -> {
                        require(shizukuState.connected) { "Shizuku 控制终端未连接" }
                        runStatus = "1/1 · 执行直接命令"
                        val command = TerminalCommandPolicy.validate(text.substringAfter(' ').trim()).getOrThrow()
                        PrivilegedBackendRouter.execute(
                            TerminalEnvironmentConfig.from(settings).wrap(command),
                            30_000L,
                        ).displayText()
                    }
                    text.startsWith("/ask ", ignoreCase = true) || text.startsWith("/chat ", ignoreCase = true) -> {
                        val question = text.substringAfter(' ').trim()
                        require(question.isNotBlank()) { "请在 /ask 后输入问题" }
                        terminalAgentReply(question, current)
                    }
                    else -> {
                        if (!agentState.accessibilityConnected) {
                            require(shizukuState.connected) { "Shizuku 与无障碍均未连接，无法控制设备" }
                            terminalAgentReply(text, current)
                        } else {
                            settings.taskGoal = text
                            PrivilegedBackendRouter.configure(context, settings.privilegedBackendEnabled)
                            when (val start = AgentController.start(context, settings, goalOverride = text)) {
                                is AgentStartResult.Started -> {
                                    val result = AgentController.awaitAndConsumeResult(
                                        start.runId,
                                        TimeUnit.MINUTES.toMillis(30),
                                    )
                                    when {
                                        result == null -> "任务超时或结果未返回。可拆小目标后重试。"
                                        result.succeeded -> "任务完成：${result.reason}"
                                        result.outcome == RuntimeOutcome.USER_CANCELLED -> "已停止当前任务。"
                                        result.outcome == RuntimeOutcome.ACCESSIBILITY_DISCONNECTED ->
                                            "无障碍服务已断开。请到 Configure 重新开启 Muse 无障碍后重试。"
                                        result.outcome == RuntimeOutcome.SAFETY_BLOCKED ->
                                            "安全策略阻止了该任务：${result.reason}"
                                        else -> runtimeFailureMessage(result.outcome, result.reason)
                                    }
                                }
                                is AgentStartResult.Busy ->
                                    "已有任务在执行（run ${start.activeRunId.take(8)}）。请先 ABORT 再发新目标。"
                                is AgentStartResult.SafetyBlocked ->
                                    "安全策略阻止了该任务：${start.reason}"
                                AgentStartResult.InvalidGoal ->
                                    "请先在 Configure 填写 API Key，并发送非空任务目标。"
                                AgentStartResult.AccessibilityDisconnected ->
                                    "无障碍刚刚断开；Shizuku 仍在线时可重发任务并走终端模式。"
                            }
                        }
                    }
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) {
                    AgentController.stop()
                    "已停止当前任务。"
                } else {
                    "执行失败：${error.message ?: error::class.java.simpleName}"
                }
            }
            progressMirror.cancel()
            val latest = conversations.firstOrNull { it.id == runConversationId } ?: withUser
            updateConversation(
                latest.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = latest.messages + ChatMessage("assistant", reply),
                ),
            )
            runStatus = ""
            activeJob = null
        }
    }

    Scaffold(
        containerColor = Void,
        topBar = {
            MuseHeader(
                page = page,
                shizukuConnected = shizukuState.connected,
                accessibilityConnected = agentState.accessibilityConnected,
                updateVersion = availableUpdate?.version,
                onNewChat = {
                    val chat = Conversation(title = "新对话")
                    persist(listOf(chat) + conversations)
                    selectedId = chat.id
                    page = MusePage.Chat
                },
                onStatusClick = { page = MusePage.Configure },
                onUpdateClick = { page = MusePage.Configure },
            )
        },
        bottomBar = {
            MuseNavigation(selected = page, onSelect = { page = it })
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            CatppuccinBackdrop()
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(220)) + slideInHorizontally(tween(280)) { direction * it / 12 }) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { -direction * it / 16 })
                },
                label = "workspace-transition",
            ) { targetPage ->
            when (targetPage) {
                MusePage.Chat -> ChatWorkspace(
                    conversation = conversations.firstOrNull { it.id == selectedId } ?: conversations.first(),
                    input = input,
                    onInputChange = { input = it.take(12_000) },
                    runStatus = runStatus.ifBlank {
                        if (agentState.running) {
                            val summary = agentState.progressSummaries.lastOrNull() ?: agentState.status
                            "${agentState.step}/${agentState.maxSteps} · $summary"
                        } else {
                            ""
                        }
                    },
                    running = activeJob?.isActive == true || agentState.running,
                    shizukuConnected = shizukuState.connected,
                    accessibilityConnected = agentState.accessibilityConnected,
                    routeLabel = when {
                        shizukuState.connected && agentState.accessibilityConnected -> "MODEL · A11Y + SHZ"
                        shizukuState.connected -> "SHIZUKU ONLY"
                        agentState.accessibilityConnected -> "A11Y NODE"
                        else -> "OFFLINE"
                    },
                    progressLines = if (agentState.running) agentState.progressSummaries else emptyList(),
                    onSend = ::sendMessage,
                    onStop = {
                        AgentController.stop()
                        activeJob?.cancel()
                    },
                )
                MusePage.Configure -> ConfigureWorkspace(
                    settings = settings,
                    environmentStatus = environmentStatus,
                    onEnvironmentStatus = { environmentStatus = it },
                    accessibilityConnected = agentState.accessibilityConnected,
                    autoUpdateEnabled = autoUpdateEnabled,
                    onAutoUpdateEnabledChange = { enabled ->
                        autoUpdateEnabled = enabled
                        settings.autoUpdateEnabled = enabled
                        if (enabled) checkForUpdates(manual = true) else updateStatus = "Auto update off"
                    },
                    availableUpdate = availableUpdate,
                    updateStatus = updateStatus,
                    checkingUpdate = checkingUpdate,
                    installingUpdate = installingUpdate,
                    updateProgress = updateProgress,
                    onCheckUpdate = { checkForUpdates(manual = true) },
                    onInstallUpdate = ::installAvailableUpdate,
                )
                MusePage.Personal -> PersonalWorkspace(settings, memoryStore, themeMode, onThemeModeChange)
            }
            }
        }
    }
}

@Composable
private fun MuseHeader(
    page: MusePage,
    shizukuConnected: Boolean,
    accessibilityConnected: Boolean,
    updateVersion: String?,
    onNewChat: () -> Unit,
    onStatusClick: () -> Unit,
    onUpdateClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceHigh),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(AcidYellow, NeonPink, NeonCyan))))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Muse",
                    color = TextPrimary,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.4).sp,
                )
                Text(
                    "${page.label}  ·  v${BuildConfig.VERSION_NAME}",
                    color = NeonPink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectionPill(
                    label = "A11Y",
                    connected = accessibilityConnected,
                    onClick = onStatusClick,
                )
                ConnectionPill(
                    label = "SHZ",
                    connected = shizukuConnected,
                    onClick = onStatusClick,
                )
                if (page == MusePage.Chat) {
                    TextButton(onClick = onNewChat) {
                        Text("New chat", color = NeonPink, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        AnimatedVisibility(updateVersion != null) {
            UpdateStrip(version = updateVersion.orEmpty(), onClick = onUpdateClick)
        }
        HorizontalDivider(color = Divider.copy(alpha = 0.7f))
    }
}

@Composable
private fun UpdateStrip(version: String, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "update-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "update-alpha",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).alpha(pulse).background(Success, CircleShape))
            Text(
                "Update available · v$version",
                color = Success,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text("Open", color = NeonPink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConnectionPill(label: String, connected: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "connection-pulse-$label")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Reverse),
        label = "connection-alpha-$label",
    )
    val accent = if (connected) Success else Warning
    Row(
        Modifier
            .border(1.dp, if (connected) accent.copy(alpha = 0.55f) else Divider, RoundedCornerShape(50))
            .background(SurfaceRaised.copy(alpha = 0.82f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .alpha(if (connected) pulse else 1f)
                .background(accent, CircleShape),
        )
        Text(
            if (connected) label else "$label off",
            color = accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MuseNavigation(selected: MusePage, onSelect: (MusePage) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceHigh)
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MusePage.entries.forEach { page ->
            val active = selected == page
            val color by animateColorAsState(if (active) NeonPink else TextSecondary, label = "nav-color")
            val background by animateColorAsState(if (active) SurfaceRaised else Color.Transparent, label = "nav-background")
            val markerWidth by animateDpAsState(if (active) 24.dp else 5.dp, spring(dampingRatio = 0.72f), label = "nav-marker")
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(page) }
                    .background(background, CompactShape)
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier
                        .size(width = markerWidth, height = 3.dp)
                        .background(color, RoundedCornerShape(50)),
                )
                Text(
                    page.label,
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ChatWorkspace(
    conversation: Conversation,
    input: String,
    onInputChange: (String) -> Unit,
    runStatus: String,
    running: Boolean,
    shizukuConnected: Boolean,
    accessibilityConnected: Boolean,
    routeLabel: String,
    progressLines: List<String>,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(runStatus.isNotBlank()) {
            ExecutionStrip(runStatus, routeLabel, progressLines)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (conversation.messages.isEmpty()) {
                item {
                    EmptyChat(
                        shizukuConnected = shizukuConnected,
                        accessibilityConnected = accessibilityConnected,
                        onSuggestion = onInputChange,
                    )
                }
            }
            items(conversation.messages) { message -> MessageBubble(message) }
        }
        HorizontalDivider(color = Divider.copy(alpha = 0.65f))
        Row(
            Modifier
                .fillMaxWidth()
                .background(SurfaceHigh)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).heightIn(min = 54.dp, max = 140.dp),
                placeholder = {
                    Text(
                        "> 设备任务 · /ask 问答 · /shell …",
                        color = TextSecondary,
                    )
                },
                label = { Text("Message or device task", color = NeonPink, fontSize = 11.sp) },
                maxLines = 5,
                shape = CyberShape,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
            )
            Button(
                onClick = if (running) onStop else onSend,
                modifier = Modifier.height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Error else NeonPink),
                shape = CompactShape,
            ) {
                Text(if (running) "Stop" else "Send", color = OnAccent, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ExecutionStrip(runStatus: String, routeLabel: String, progressLines: List<String>) {
    val budget = runStatus.substringBefore('·').trim()
    val maxSteps = budget.substringAfter('/', "50").trim().toIntOrNull()?.coerceAtLeast(1) ?: 50
    val step = budget.substringBefore('/').trim().toIntOrNull()?.coerceIn(0, maxSteps) ?: 0
    val fallback = runStatus.substringAfter('·', runStatus).trim()
    val visibleLines = progressLines.filter { it.isNotBlank() }.takeLast(2).ifEmpty { listOf(fallback) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(SurfaceLow, CyberShape)
            .border(1.dp, Divider, CyberShape)
            .animateContentSize(animationSpec = spring(dampingRatio = 0.78f))
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(7.dp).background(Teal, CircleShape))
                Text("Muse is running · $routeLabel", color = Teal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                step.toString().padStart(2, '0') + " / $maxSteps ACTIONS",
                color = NeonPink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        visibleLines.forEach { line ->
            Text(
                line,
                color = TextPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(
    shizukuConnected: Boolean,
    accessibilityConnected: Boolean,
    onSuggestion: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("今天想让 Muse 做什么？", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp)
        Text("模型自己找路  ·  无障碍点按  ·  Shizuku 管设备", color = NeonPink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            when {
                accessibilityConnected && shizukuConnected ->
                    "无障碍与 Shizuku 都已就绪。直接说一句自然语言任务就行，不必开视觉模型。"
                accessibilityConnected ->
                    "无障碍已连接。再到 Configure 接上 Shizuku，就能启动应用和跑终端。"
                shizukuConnected ->
                    "Shizuku 已连接。打开无障碍后，Muse 才能看懂并点击页面上的控件。"
                else ->
                    "先到 Configure 打开无障碍并连接 Shizuku。也可以先用 /ask 问答，或 /shell 跑命令。"
            },
            color = TextSecondary,
            lineHeight = 22.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "打开设置，打开 WLAN",
                "回到桌面",
                "/ask 解释一下这段日志",
                "/shell id",
            ).forEach { suggestion ->
                SuggestionChip(suggestion) { onSuggestion(suggestion) }
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .border(1.dp, Divider, RoundedCornerShape(50))
            .background(SurfaceLow, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        color = TextPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    val accent = if (user) NeonPink else NeonCyan
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(if (user) 0.86f else 0.94f)
                .border(1.dp, if (user) NeonPink.copy(alpha = 0.38f) else Divider.copy(alpha = 0.8f), CyberShape)
                .background(if (user) NeonPink.copy(alpha = 0.12f) else SurfaceLow, CyberShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                if (user) "You" else "Muse",
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(message.content, color = TextPrimary, lineHeight = 21.sp)
        }
    }
}

@Composable
private fun ConfigureWorkspace(
    settings: SecureSettings,
    environmentStatus: Map<String, String>,
    onEnvironmentStatus: (Map<String, String>) -> Unit,
    accessibilityConnected: Boolean,
    autoUpdateEnabled: Boolean,
    onAutoUpdateEnabledChange: (Boolean) -> Unit,
    availableUpdate: UpdateInfo?,
    updateStatus: String,
    checkingUpdate: Boolean,
    installingUpdate: Boolean,
    updateProgress: DownloadProgress?,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ShizukuBridge.state.collectAsState()
    var provider by remember { mutableStateOf(settings.currentProvider) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var baseUrl by remember { mutableStateOf(settings.modelBaseUrl) }
    var model by remember { mutableStateOf(settings.modelName) }
    var workingDirectory by remember { mutableStateOf(settings.terminalWorkingDirectory) }
    var pathPrefix by remember { mutableStateOf(settings.terminalPathPrefix) }
    var enabledTools by remember { mutableStateOf(settings.enabledTerminalTools) }
    var selectedMirrorId by remember { mutableStateOf(settings.environmentMirrorId) }
    var installedEnvironment by remember { mutableStateOf<InstalledLinuxEnvironment?>(null) }
    var installProgress by remember { mutableStateOf<EnvironmentInstallProgress?>(null) }
    var installing by remember { mutableStateOf(false) }
    var toolCommands by remember {
        mutableStateOf(TERMINAL_TOOLS.associate { it.id to settings.terminalToolCommand(it.id, it.defaultCommand) })
    }
    var feedback by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }

    LaunchedEffect(state.connected) {
        if (state.connected) {
            installedEnvironment = EmbeddedLinuxEnvironment.inspect()
            installedEnvironment?.let { selectedMirrorId = it.mirrorId }
        } else {
            installedEnvironment = null
        }
    }

    fun saveConfiguration() {
        settings.currentProvider = provider
        settings.apiKey = apiKey
        settings.modelBaseUrl = baseUrl
        settings.modelName = model
        settings.terminalWorkingDirectory = workingDirectory
        settings.terminalPathPrefix = pathPrefix
        settings.environmentMirrorId = selectedMirrorId
        settings.enabledTerminalTools = enabledTools
        TERMINAL_TOOLS.forEach { tool -> settings.setTerminalToolCommand(tool.id, toolCommands[tool.id].orEmpty()) }
        PrivilegedBackendRouter.configure(context, true)
        feedback = "配置已保存"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsSection("APP UPDATE", "启动时检查 GitHub Release；安装前校验 APK SHA-256") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Current · v${BuildConfig.VERSION_NAME}", color = NeonCyan, fontWeight = FontWeight.Bold)
                    Text("Automatic checks", color = TextSecondary, fontSize = 10.sp)
                }
                Switch(checked = autoUpdateEnabled, onCheckedChange = onAutoUpdateEnabledChange)
            }
            HorizontalDivider(color = Divider)
            Text(
                updateStatus,
                color = if (updateStatus.contains("失败")) Error else if (availableUpdate == null) TextSecondary else NeonPink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            updateProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = NeonPink,
                    trackColor = Divider,
                )
                Text(
                    "Download ${progress.percent}% · ${progress.downloadedBytes / 1_024 / 1_024} / ${progress.totalBytes / 1_024 / 1_024} MiB",
                    color = NeonPink,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            OutlinedButton(
                onClick = onCheckUpdate,
                enabled = !checkingUpdate && !installingUpdate,
                modifier = Modifier.fillMaxWidth(),
                shape = CyberShape,
            ) {
                Text(if (checkingUpdate) "CHECKING..." else "检查更新", fontFamily = FontFamily.Monospace)
            }
            AnimatedVisibility(availableUpdate != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val update = availableUpdate
                    if (update != null) {
                        Text("Available · v${update.version}", color = NeonPink, fontWeight = FontWeight.Bold)
                        if (update.notes.isNotBlank()) {
                            Text(
                                update.notes.take(1_200),
                                color = TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "SHA-256 · ${update.sha256.take(12)}…${update.sha256.takeLast(8)}",
                            color = AcidYellow,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Button(
                            onClick = onInstallUpdate,
                            enabled = !installingUpdate && !checkingUpdate,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                            shape = CyberShape,
                        ) {
                            Text(
                                when {
                                    installingUpdate -> "DOWNLOADING..."
                                    updateStatus.startsWith("APK 已校验") -> "继续安装"
                                    else -> "下载 · 校验 · 安装"
                                },
                                color = Void,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }

        SettingsSection("ACCESSIBILITY", "设备 UI 工具通道：控件树观察 / 点击 / 滑动 / 跨页进度浮层") {
            StatusLine("Service connected", accessibilityConnected)
            StatusLine("Live instance", AgentAccessibilityService.current() != null)
            Text(
                if (accessibilityConnected) {
                    "无障碍已连接。发送自然语言设备任务时，模型可使用 launch_app / click_node / tap_point / swipe / input_text / terminal 等工具；DeepSeek 默认仅用节点树（无截图视觉）。"
                } else {
                    "请开启系统设置中的 Muse 无障碍服务。开启后，在其它 App 上层会显示柔和的运行状态浮层与停止按钮。"
                },
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Button(
                onClick = {
                    feedback = if (openAccessibilitySettings(context)) {
                        "已打开无障碍设置，请找到 Muse 并开启"
                    } else {
                        "无法打开无障碍设置，请手动到 设置 → 无障碍 → 已安装的服务"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (accessibilityConnected) NeonCyan else NeonPink),
                shape = CyberShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (accessibilityConnected) "重新打开无障碍设置" else "开启无障碍",
                    color = Void,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        SettingsSection("SHIZUKU", "高权限 shell / 启动应用 / 终端环境") {
            StatusLine("Binder", state.binderAvailable)
            StatusLine("App permission", state.permissionGranted)
            StatusLine("Control terminal", state.connected)
            Text(state.detail, color = TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        when {
                            !state.binderAvailable -> {
                                feedback = if (ShizukuBridge.openManager(context)) "请启动 Shizuku 后返回" else "未安装 Shizuku"
                            }
                            !state.permissionGranted -> ShizukuBridge.requestPermission()
                            !state.connected -> ShizukuBridge.connect()
                            else -> scope.launch { feedback = ShizukuBridge.testConnection().displayText() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = CyberShape,
                ) {
                    Text(
                        when {
                            !state.binderAvailable -> "打开 Shizuku"
                            !state.permissionGranted -> "授权"
                            !state.connected -> "连接"
                            else -> "测试连接"
                        },
                        color = Void,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        SettingsSection("MODEL", "OpenAI-compatible Chat Completions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("deepseek" to "DeepSeek", "qwen" to "Qwen", "mimo" to "MiMo").forEach { (id, label) ->
                    OutlinedButton(
                        onClick = {
                            provider = id
                            when (id) {
                                "qwen" -> { baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"; model = "qwen3.6-flash" }
                                "mimo" -> { baseUrl = "https://api.xiaomimimo.com/v1"; model = "mimo-v2-flash" }
                                else -> { baseUrl = SecureSettings.DEFAULT_BASE_URL; model = SecureSettings.DEFAULT_MODEL }
                            }
                            apiKey = settings.apiKeyFor(id)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (provider == id) NeonPink.copy(alpha = 0.13f) else Color.Transparent,
                            contentColor = if (provider == id) NeonPink else TextSecondary,
                        ),
                        shape = CyberShape,
                    ) { Text(label) }
                }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = CyberShape,
            )
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = CyberShape)
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = CyberShape)
        }

        SettingsSection("INITIAL ENVIRONMENT", "安装 Ubuntu ARM64，并把运行时接入 Shizuku 控制终端") {
            val installed = installedEnvironment
            Text(
                if (installed == null) "尚未安装" else "Ubuntu ${installed.version} · ${installed.tools.sorted().joinToString(" / ")}",
                color = if (installed == null) Warning else NeonCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text("镜像源同时用于 Ubuntu Base 下载和 ubuntu-ports 软件包", color = TextSecondary, fontSize = 11.sp)
            EmbeddedLinuxEnvironment.mirrors.forEach { mirror ->
                OutlinedButton(
                    onClick = { selectedMirrorId = mirror.id },
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selectedMirrorId == mirror.id) NeonCyan.copy(alpha = 0.1f) else Color.Transparent,
                        contentColor = if (selectedMirrorId == mirror.id) NeonCyan else TextSecondary,
                    ),
                    shape = CyberShape,
                ) {
                    Text(
                        if (selectedMirrorId == mirror.id) "[●] ${mirror.label}" else "[ ] ${mirror.label}",
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(
                "Ubuntu ${EmbeddedLinuxEnvironment.ROOTFS_VERSION} · arm64-v8a · SHA-256 verified",
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            OutlinedTextField(
                value = workingDirectory,
                onValueChange = { workingDirectory = it },
                label = { Text("Working directory") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = CyberShape,
            )
            OutlinedTextField(
                value = pathPrefix,
                onValueChange = { pathPrefix = it },
                label = { Text("PATH prefix (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = CyberShape,
            )
            TERMINAL_TOOLS.forEach { tool ->
                ToolConfigurationRow(
                    label = tool.label,
                    enabled = tool.id in enabledTools,
                    command = toolCommands[tool.id].orEmpty(),
                    detectedPath = environmentStatus[tool.id],
                    onEnabledChange = { enabled ->
                        enabledTools = if (enabled) enabledTools + tool.id else enabledTools - tool.id
                    },
                    onCommandChange = { value -> toolCommands = toolCommands + (tool.id to value) },
                )
            }
            installProgress?.let { progress ->
                if (progress.fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeonPink, trackColor = Divider)
                } else {
                    LinearProgressIndicator(
                        progress = { progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonPink,
                        trackColor = Divider,
                    )
                }
                Text(progress.message, color = NeonPink, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Button(
                enabled = state.connected && !installing && enabledTools.any { it != "shell" },
                onClick = {
                    saveConfiguration()
                    val mirror = EmbeddedLinuxEnvironment.mirrorById(selectedMirrorId)
                    if (mirror == null) {
                        feedback = "未知镜像源"
                    } else {
                        installing = true
                        installProgress = EnvironmentInstallProgress(0f, "准备安装")
                        scope.launch {
                            val result = runCatching {
                                EmbeddedLinuxEnvironment.install(
                                    context = context,
                                    mirror = mirror,
                                    tools = enabledTools - "shell",
                                    onProgress = { progress -> scope.launch { installProgress = progress } },
                                )
                            }.getOrElse { error ->
                                feedback = "环境安装失败：${error.message ?: error::class.java.simpleName}"
                                null
                            }
                            if (result != null) {
                                feedback = if (result.success) "环境安装完成" else "环境安装失败：${result.error ?: result.stderr.ifBlank { "未知错误" }}"
                                if (result.success) {
                                    installedEnvironment = EmbeddedLinuxEnvironment.inspect()
                                    onEnvironmentStatus(TerminalEnvironmentProbe.probe(TerminalEnvironmentConfig.from(settings)))
                                }
                            }
                            installing = false
                            if (result?.success != true) installProgress = null
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                shape = CyberShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        installing -> "INSTALLING..."
                        installed == null -> "下载并安装环境"
                        else -> "修复 / 重新安装环境"
                    },
                    color = Void,
                    fontWeight = FontWeight.Black,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    enabled = state.connected && !probing,
                    shape = CyberShape,
                    onClick = {
                        saveConfiguration()
                        probing = true
                        scope.launch {
                            onEnvironmentStatus(TerminalEnvironmentProbe.probe(TerminalEnvironmentConfig.from(settings)))
                            feedback = "环境探测完成"
                            probing = false
                        }
                    },
                ) { Text(if (probing) "探测中…" else "探测环境") }
                Button(onClick = ::saveConfiguration, colors = ButtonDefaults.buttonColors(containerColor = NeonCyan), shape = CyberShape) {
                    Text("保存配置", color = Void, fontWeight = FontWeight.Bold)
                }
            }
        }

        AnimatedVisibility(feedback.isNotBlank()) {
            Text(feedback, color = if (feedback.startsWith("未") || feedback.contains("失败")) Error else Success, fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ToolConfigurationRow(
    label: String,
    enabled: Boolean,
    command: String,
    detectedPath: String?,
    onEnabledChange: (Boolean) -> Unit,
    onCommandChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(detectedPath ?: "未探测", color = if (detectedPath == null) TextSecondary else NeonCyan, fontSize = 11.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        AnimatedVisibility(enabled) {
            OutlinedTextField(
                value = command,
                onValueChange = onCommandChange,
                label = { Text("Executable") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = CyberShape,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        HorizontalDivider(color = Divider)
    }
}

@Composable
private fun PersonalWorkspace(
    settings: SecureSettings,
    memoryStore: PersonalizationStore,
    themeMode: MuseThemeMode,
    onThemeModeChange: (MuseThemeMode) -> Unit,
) {
    var memory by remember { mutableStateOf(memoryStore.loadMemory()) }
    var contextLength by remember { mutableStateOf(settings.contextLength.toString()) }
    var maxTokens by remember { mutableStateOf(settings.maxOutputTokens.toString()) }
    var feedback by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsSection("APPEARANCE", "Catppuccin：浅色是晒过的 Latte，深色是原版 Mocha") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice(
                    label = "System",
                    selected = themeMode == MuseThemeMode.SYSTEM,
                    swatches = listOf(Color(0xFFF6F0E6), Color(0xFF1E1E2E), Color(0xFFCBA6F7)),
                    modifier = Modifier.weight(1f),
                ) { onThemeModeChange(MuseThemeMode.SYSTEM) }
                ThemeChoice(
                    label = "Light",
                    selected = themeMode == MuseThemeMode.LIGHT,
                    swatches = listOf(Color(0xFFF6F0E6), Color(0xFFC96B4A), Color(0xFFA36BB5)),
                    modifier = Modifier.weight(1f),
                ) { onThemeModeChange(MuseThemeMode.LIGHT) }
                ThemeChoice(
                    label = "Dark",
                    selected = themeMode == MuseThemeMode.DARK,
                    swatches = listOf(Color(0xFF1E1E2E), Color(0xFF89B4FA), Color(0xFFCBA6F7)),
                    modifier = Modifier.weight(1f),
                ) { onThemeModeChange(MuseThemeMode.DARK) }
            }
        }
        SettingsSection("USER MEMORY", "Markdown 会作为稳定偏好注入每次新请求") {
            OutlinedTextField(
                value = memory,
                onValueChange = { memory = it.take(PersonalizationStore.MAX_MEMORY_CHARS) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                label = { Text("memory.md") },
                shape = CyberShape,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text(memoryStore.absolutePath(), color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        SettingsSection("MODEL BUDGET", "上下文在本地裁剪；输出限制会发送给模型服务") {
            OutlinedTextField(
                value = contextLength,
                onValueChange = { contextLength = it.filter(Char::isDigit).take(6) },
                label = { Text("Context length · 4,096–128,000") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = CyberShape,
            )
            OutlinedTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it.filter(Char::isDigit).take(5) },
                label = { Text("Max output tokens · 256–16,384") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = CyberShape,
            )
            Button(
                onClick = {
                    memoryStore.saveMemory(memory)
                    settings.contextLength = contextLength.toIntOrNull() ?: SecureSettings.DEFAULT_CONTEXT_LENGTH
                    settings.maxOutputTokens = maxTokens.toIntOrNull() ?: SecureSettings.DEFAULT_MAX_OUTPUT_TOKENS
                    contextLength = settings.contextLength.toString()
                    maxTokens = settings.maxOutputTokens.toString()
                    feedback = "个性化配置已保存"
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                shape = CyberShape,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存个性化", color = Void, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
            AnimatedVisibility(feedback.isNotBlank()) {
                Text(feedback, color = Success, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ThemeChoice(
    label: String,
    selected: Boolean,
    swatches: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 58.dp),
        shape = CompactShape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) NeonPink.copy(alpha = 0.16f) else SurfaceLow,
            contentColor = if (selected) NeonPink else TextSecondary,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) NeonPink else Divider,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                swatches.forEach { color ->
                    Box(Modifier.size(10.dp).background(color, CircleShape).border(0.5.dp, Divider.copy(alpha = 0.5f), CircleShape))
                }
            }
            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Divider.copy(alpha = 0.82f), CyberShape)
            .background(SurfaceLow, CyberShape)
            .padding(horizontal = 16.dp, vertical = 17.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
        HorizontalDivider(color = Divider.copy(alpha = 0.7f))
        content()
    }
}

@Composable
private fun StatusLine(label: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontWeight = FontWeight.Medium)
        Text(
            if (ready) "Online" else "Standby",
            modifier = Modifier.background(
                if (ready) Success.copy(alpha = 0.13f) else Warning.copy(alpha = 0.1f),
                RoundedCornerShape(50),
            ).padding(horizontal = 10.dp, vertical = 5.dp),
            color = if (ready) Success else Warning,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun openAccessibilitySettings(context: Context): Boolean {
    val intents = listOf(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (launched) return true
    }
    return false
}

private fun runtimeFailureMessage(outcome: RuntimeOutcome, reason: String): String {
    val detail = reason.lowercase()
    return when {
        outcome == RuntimeOutcome.PERMANENT_PLAN_ERROR &&
            ("manager plan" in detail || "predicate" in detail || "valueref" in detail) ->
            "任务规划未通过协议校验，Muse 已安全停止，没有继续操作设备。"
        outcome == RuntimeOutcome.PERMANENT_PLAN_ERROR &&
            ("control-cycle" in detail || "without verified completion" in detail) ->
            "任务没有取得可验证的完成证据，Muse 已停止以避免重复执行。"
        outcome == RuntimeOutcome.TRANSIENT_NETWORK_ERROR ->
            "模型服务暂时不可用，本次任务未继续执行。"
        outcome == RuntimeOutcome.TIMEOUT ->
            "任务运行超时，Muse 已停止当前执行。"
        else -> "任务未完成，Muse 已安全停止。可在运行记录中查看诊断信息。"
    }
}
