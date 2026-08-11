package com.androidagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidagent.app.chat.ChatMessage
import com.androidagent.app.chat.ChatStore
import com.androidagent.app.chat.Conversation
import com.androidagent.app.data.PersonalizationStore
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.network.TerminalAgentClient
import com.androidagent.app.network.TerminalCommandPolicy
import com.androidagent.app.network.TERMINAL_TOOL_TURN_LIMIT
import com.androidagent.app.privileged.PrivilegedBackendRouter
import com.androidagent.app.privileged.ShizukuBridge
import com.androidagent.app.terminal.TERMINAL_TOOLS
import com.androidagent.app.terminal.EmbeddedLinuxEnvironment
import com.androidagent.app.terminal.EnvironmentInstallProgress
import com.androidagent.app.terminal.InstalledLinuxEnvironment
import com.androidagent.app.terminal.TerminalEnvironmentConfig
import com.androidagent.app.terminal.TerminalEnvironmentProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val Void = Color(0xFF010408)
private val SurfaceLow = Color(0xE8071018)
private val SurfaceHigh = Color(0xF20A1720)
private val NeonCyan = Color(0xFF00F0FF)
private val NeonPink = Color(0xFFFF3DF2)
private val AcidYellow = Color(0xFFF2FF59)
private val TextPrimary = Color(0xFFE9FCFF)
private val TextSecondary = Color(0xFF7E9BA6)
private val Divider = Color(0xFF123746)
private val Warning = Color(0xFFF2FF59)
private val Error = Color(0xFFFF416C)
private val CyberShape = CutCornerShape(topStart = 0.dp, topEnd = 12.dp, bottomEnd = 0.dp, bottomStart = 12.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuBridge.initialize(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = NeonCyan,
                    secondary = NeonPink,
                    tertiary = AcidYellow,
                    background = Void,
                    surface = SurfaceLow,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary,
                ),
            ) {
                MuseApp()
            }
        }
    }
}

private enum class MusePage(val label: String) {
    Chat("CHAT://"),
    Configure("CONFIG://"),
    Personal("PERSONA://"),
}

@Composable
private fun CyberBackdrop() {
    val transition = rememberInfiniteTransition(label = "hud-scan")
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_800, easing = LinearEasing), RepeatMode.Restart),
        label = "scan-position",
    )
    Canvas(Modifier.fillMaxSize()) {
        val grid = 42.dp.toPx()
        var x = 0f
        while (x <= size.width) {
            drawLine(Divider.copy(alpha = 0.18f), Offset(x, 0f), Offset(x, size.height), 1f)
            x += grid
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(Divider.copy(alpha = 0.14f), Offset(0f, y), Offset(size.width, y), 1f)
            y += grid
        }
        val scanY = size.height * scan
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, NeonCyan.copy(alpha = 0.08f), Color.Transparent),
                startY = scanY - 32.dp.toPx(),
                endY = scanY + 32.dp.toPx(),
            ),
            topLeft = Offset(0f, scanY - 32.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(size.width, 64.dp.toPx()),
        )
        drawLine(NeonCyan.copy(alpha = 0.22f), Offset(0f, scanY), Offset(size.width, scanY), 1.dp.toPx())
    }
}

@Composable
private fun MuseApp() {
    val context = LocalContext.current
    val settings = remember { SecureSettings(context) }
    val memoryStore = remember { PersonalizationStore(context) }
    val chatStore = remember { ChatStore(context) }
    val scope = rememberCoroutineScope()
    val shizukuState by ShizukuBridge.state.collectAsState()

    var page by remember { mutableStateOf(MusePage.Chat) }
    var conversations by remember {
        mutableStateOf(chatStore.load().ifEmpty { listOf(Conversation(title = "新对话")) })
    }
    var selectedId by remember { mutableStateOf(conversations.first().id) }
    var input by remember { mutableStateOf("") }
    var runStatus by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var environmentStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        PrivilegedBackendRouter.configure(context, true)
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

    fun sendMessage() {
        val text = input.trim()
        if (text.isBlank() || activeJob?.isActive == true) return
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
            val reply = runCatching {
                if (text.startsWith("/shell ", ignoreCase = true)) {
                    require(shizukuState.connected) { "Shizuku 控制终端未连接" }
                    runStatus = "执行直接命令"
                    val command = TerminalCommandPolicy.validate(text.substringAfter(' ').trim()).getOrThrow()
                    PrivilegedBackendRouter.execute(
                        TerminalEnvironmentConfig.from(settings).wrap(command),
                        30_000L,
                    ).displayText()
                } else {
                    TerminalAgentClient().respond(
                        apiKey = settings.apiKey,
                        baseUrl = settings.modelBaseUrl,
                        model = settings.modelName,
                        provider = settings.currentProvider,
                        input = text,
                        history = current.messages.map { it.role to it.content },
                        memoryMarkdown = memoryStore.loadMemory(),
                        contextLength = settings.contextLength,
                        maxOutputTokens = settings.maxOutputTokens,
                        environment = TerminalEnvironmentConfig.from(settings),
                        environmentStatus = environmentStatus,
                        terminalAvailable = shizukuState.connected,
                        execute = PrivilegedBackendRouter::execute,
                        onProgress = { progress -> scope.launch { runStatus = progress } },
                    )
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) "已停止当前任务。"
                else "执行失败：${error.message ?: error::class.java.simpleName}"
            }
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
                connected = shizukuState.connected,
                onNewChat = {
                    val chat = Conversation(title = "新对话")
                    persist(listOf(chat) + conversations)
                    selectedId = chat.id
                    page = MusePage.Chat
                },
                onStatusClick = { page = MusePage.Configure },
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
            CyberBackdrop()
            when (page) {
                MusePage.Chat -> ChatWorkspace(
                    conversation = conversations.firstOrNull { it.id == selectedId } ?: conversations.first(),
                    input = input,
                    onInputChange = { input = it.take(12_000) },
                    runStatus = runStatus,
                    running = activeJob?.isActive == true,
                    connected = shizukuState.connected,
                    onSend = ::sendMessage,
                    onStop = { activeJob?.cancel() },
                )
                MusePage.Configure -> ConfigureWorkspace(
                    settings = settings,
                    environmentStatus = environmentStatus,
                    onEnvironmentStatus = { environmentStatus = it },
                )
                MusePage.Personal -> PersonalWorkspace(settings, memoryStore)
            }
        }
    }
}

@Composable
private fun MuseHeader(
    page: MusePage,
    connected: Boolean,
    onNewChat: () -> Unit,
    onStatusClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(SurfaceHigh, Color(0xF2060B12), SurfaceHigh),
                ),
            ),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(NeonCyan, NeonPink))))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "MUSE//OS",
                    color = NeonCyan,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                )
                Text(
                    "${page.label}  NEURAL TERMINAL · V${BuildConfig.VERSION_NAME}",
                    color = NeonPink,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.7.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectionPill(connected = connected, onClick = onStatusClick)
                if (page == MusePage.Chat) {
                    TextButton(onClick = onNewChat) {
                        Text("＋NEW", color = NeonPink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        HorizontalDivider(color = NeonCyan.copy(alpha = 0.32f))
    }
}

@Composable
private fun ConnectionPill(connected: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "connection-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Reverse),
        label = "connection-alpha",
    )
    Row(
        Modifier
            .border(1.dp, if (connected) NeonCyan else Warning, CyberShape)
            .background(if (connected) NeonCyan.copy(alpha = 0.08f) else Warning.copy(alpha = 0.08f), CyberShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .alpha(if (connected) pulse else 1f)
                .background(if (connected) NeonCyan else Warning, CircleShape),
        )
        Text(
            if (connected) "LINK//ON" else "LINK//OFF",
            color = if (connected) NeonCyan else Warning,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MuseNavigation(selected: MusePage, onSelect: (MusePage) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceLow)
            .border(width = 1.dp, color = NeonCyan.copy(alpha = 0.18f))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        MusePage.entries.forEach { page ->
            val color by animateColorAsState(if (selected == page) NeonCyan else TextSecondary, label = "nav-color")
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(page) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier
                        .size(width = if (selected == page) 34.dp else 6.dp, height = 2.dp)
                        .background(if (selected == page) Brush.horizontalGradient(listOf(NeonCyan, NeonPink)) else Brush.linearGradient(listOf(color, color))),
                )
                Text(
                    page.label,
                    color = color,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (selected == page) FontWeight.Black else FontWeight.Normal,
                    letterSpacing = 0.5.sp,
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
    connected: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(runStatus.isNotBlank()) {
            ExecutionStrip(runStatus)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (conversation.messages.isEmpty()) {
                item {
                    EmptyChat(connected)
                }
            }
            items(conversation.messages) { message -> MessageBubble(message) }
        }
        HorizontalDivider(color = Divider)
        Row(
            Modifier
                .fillMaxWidth()
                .background(SurfaceLow)
                .border(1.dp, NeonCyan.copy(alpha = 0.2f))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).heightIn(min = 54.dp, max = 140.dp),
                placeholder = { Text("> REQUEST OR /shell …", color = TextSecondary, fontFamily = FontFamily.Monospace) },
                label = { Text("COMMAND_INPUT", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                maxLines = 5,
                shape = CyberShape,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = TextPrimary),
            )
            Button(
                onClick = if (running) onStop else onSend,
                modifier = Modifier.height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Error else NeonCyan),
                shape = CyberShape,
            ) {
                Text(if (running) "ABORT" else "SEND", color = Void, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ExecutionStrip(runStatus: String) {
    val step = runStatus.substringBefore('/').trim().toIntOrNull()?.coerceIn(0, TERMINAL_TOOL_TURN_LIMIT) ?: 0
    Column(
        Modifier
            .fillMaxWidth()
            .background(NeonCyan.copy(alpha = 0.07f))
            .border(1.dp, NeonCyan.copy(alpha = 0.35f))
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = NeonPink)
                Text("EXEC_CHAIN", color = NeonPink, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            Text(
                step.toString().padStart(2, '0') + " / $TERMINAL_TOOL_TURN_LIMIT",
                color = AcidYellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
        }
        LinearProgressIndicator(
            progress = { step.toFloat() / TERMINAL_TOOL_TURN_LIMIT },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = NeonCyan,
            trackColor = Divider,
        )
        Text(runStatus.substringAfter('·', runStatus).trim(), color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyChat(connected: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 54.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("NEURAL CONTROL", color = NeonCyan, fontSize = 29.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        Text("ANDROID // SHIZUKU // MODEL", color = NeonPink, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        Text(
            if (connected) "Shizuku 已连接。Muse 可以对话，也可以在安全边界内调用终端操作手机。"
            else "先到 Configure 连接 Shizuku。普通对话仍可使用，终端操作会等待连接。",
            color = TextSecondary,
            lineHeight = 22.sp,
        )
        Text("> QUICK_TEST: /shell id", color = AcidYellow, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("MAX_CHAIN: $TERMINAL_TOOL_TURN_LIMIT", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    val accent = if (user) NeonPink else NeonCyan
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(if (user) 0.84f else 0.94f)
                .border(1.dp, accent.copy(alpha = 0.72f), CyberShape)
                .background(accent.copy(alpha = if (user) 0.1f else 0.06f), CyberShape)
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                if (user) "[ OPERATOR ]" else "[ MUSE_CORE ]",
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
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
        SettingsSection("SHIZUKU", "唯一的高权限执行通道") {
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
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (provider == id) NeonCyan else TextSecondary),
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
                if (installed == null) "STATUS // 未安装" else "STATUS // Ubuntu ${installed.version} · ${installed.tools.sorted().joinToString(" / ")}",
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

        if (feedback.isNotBlank()) {
            Text(feedback, color = if (feedback.startsWith("未") || feedback.contains("失败")) Error else NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
private fun PersonalWorkspace(settings: SecureSettings, memoryStore: PersonalizationStore) {
    var memory by remember { mutableStateOf(memoryStore.loadMemory()) }
    var contextLength by remember { mutableStateOf(settings.contextLength.toString()) }
    var maxTokens by remember { mutableStateOf(settings.maxOutputTokens.toString()) }
    var feedback by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
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
            ) { Text("保存个性化", color = Void, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
            if (feedback.isNotBlank()) Text(feedback, color = NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(18.dp))
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
            .border(1.dp, Divider, CyberShape)
            .background(SurfaceLow, CyberShape)
            .padding(horizontal = 14.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("// $title", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp)
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }
            Text("◆", color = NeonPink, fontSize = 10.sp)
        }
        HorizontalDivider(color = NeonCyan.copy(alpha = 0.22f))
        content()
    }
}

@Composable
private fun StatusLine(label: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("> $label", color = TextPrimary, fontFamily = FontFamily.Monospace)
        Text(if (ready) "[ ONLINE ]" else "[ STANDBY ]", color = if (ready) NeonCyan else Warning, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}
