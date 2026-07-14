package com.androidagent.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.androidagent.app.accessibility.AgentController
import com.androidagent.app.agent.AgentTraceStore
import com.androidagent.app.agent.AgentUiState
import com.androidagent.app.automation.ScheduleCommandParser
import com.androidagent.app.automation.ScheduledTaskScheduler
import com.androidagent.app.apps.AppCatalog
import com.androidagent.app.chat.ChatMessage
import com.androidagent.app.chat.ChatStore
import com.androidagent.app.chat.Conversation
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.network.DeepSeekClient
import com.androidagent.app.network.InteractionDecision
import com.androidagent.app.update.GitHubUpdater
import com.androidagent.app.update.UpdateInfo
import com.androidagent.app.update.DownloadProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF17211B)
private val Canvas = Color(0xFFF7F7F2)
private val Accent = Color(0xFF16724B)
private val Muted = Color(0xFF68736C)
private val Line = Color(0xFFDDE2DC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AgentChatApp(openAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            }
        }
    }
}

@Composable
private fun AgentChatApp(openAccessibilitySettings: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SecureSettings(context) }
    val chatStore = remember { ChatStore(context) }
    val catalog = remember { AppCatalog(context) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val agentState by AgentController.state.collectAsState()
    var conversations by remember {
        mutableStateOf(chatStore.load().ifEmpty { listOf(Conversation()) })
    }
    var selectedId by remember { mutableStateOf(conversations.first().id) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var activeProvider by remember { mutableStateOf(settings.currentProvider) }
    var defaultPackage by remember { mutableStateOf(settings.targetPackage) }
    var githubRepository by remember { mutableStateOf(settings.githubRepository) }
    var modelBaseUrl by remember { mutableStateOf(settings.modelBaseUrl) }
    var modelName by remember { mutableStateOf(settings.modelName) }
    var visionEnabled by remember { mutableStateOf(settings.visionEnabled) }
    var visionApiKey by remember { mutableStateOf(settings.visionApiKey) }
    var visionBaseUrl by remember { mutableStateOf(settings.visionBaseUrl) }
    var visionModelName by remember { mutableStateOf(settings.visionModelName) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    var nextRunAt by remember { mutableStateOf(settings.nextRunAt) }
    val apps = remember { catalog.list() }
    val updater = remember { GitHubUpdater(context) }

    LaunchedEffect(githubRepository) {
        if (githubRepository.isNotBlank()) {
            runCatching { updater.check(githubRepository) }
                .onSuccess { availableUpdate = it }
                .onFailure { updateMessage = "更新检查失败：${it.message}" }
        }
    }
    LaunchedEffect(agentState.status) {
        if (!agentState.running) nextRunAt = settings.nextRunAt
    }

    fun persist(updated: List<Conversation>) {
        conversations = updated
        chatStore.save(updated)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Ink, modifier = Modifier.width(330.dp)) {
                DrawerContent(
                    conversations = conversations,
                    selectedId = selectedId,
                    apiKey = apiKey,
                    activeProvider = activeProvider,
                    defaultPackage = defaultPackage,
                    githubRepository = githubRepository,
                    modelBaseUrl = modelBaseUrl,
                    modelName = modelName,
                    visionEnabled = visionEnabled,
                    visionApiKey = visionApiKey,
                    visionBaseUrl = visionBaseUrl,
                    visionModelName = visionModelName,
                    appCount = apps.size,
                    connected = AgentController.state.value.accessibilityConnected,
                    nextRunAt = nextRunAt,
                    onSelect = { selectedId = it; scope.launch { drawerState.close() } },
                    onNew = {
                        val chat = Conversation()
                        persist(listOf(chat) + conversations)
                        selectedId = chat.id
                        scope.launch { drawerState.close() }
                    },
                    onPin = { id -> persist(conversations.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }) },
                    onDelete = { id ->
                        val remaining = conversations.filterNot { it.id == id }.ifEmpty { listOf(Conversation()) }
                        persist(remaining)
                        if (selectedId == id) selectedId = remaining.first().id
                    },
                    onApiKey = { apiKey = it; settings.apiKey = it },
                    onDefaultPackage = { defaultPackage = it; settings.targetPackage = it },
                    onGithubRepository = { githubRepository = it; settings.githubRepository = it },
                    onModelBaseUrl = { modelBaseUrl = it; settings.modelBaseUrl = it },
                    onModelName = { modelName = it; settings.modelName = it },
                    onVisionEnabled = { visionEnabled = it; settings.visionEnabled = it },
                    onVisionApiKey = { visionApiKey = it; settings.visionApiKey = it },
                    onVisionBaseUrl = { visionBaseUrl = it; settings.visionBaseUrl = it },
                    onVisionModelName = { visionModelName = it; settings.visionModelName = it },
                    onModelPreset = { preset ->
                        val values = when (preset) {
                            "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1" to "qwen3.6-flash"
                            "mimo" -> "https://dashscope.aliyuncs.com/compatible-mode/v1" to "mimo-v2.5-pro"
                            else -> "https://api.deepseek.com" to "deepseek-v4-flash"
                        }
                        settings.currentProvider = preset
                        activeProvider = preset
                        modelBaseUrl = values.first
                        modelName = values.second
                        settings.modelBaseUrl = values.first
                        settings.modelName = values.second
                        apiKey = settings.apiKey
                    },
                    onCancelSchedule = {
                        ScheduledTaskScheduler.cancel(context)
                        nextRunAt = 0L
                    },
                    openAccessibilitySettings = openAccessibilitySettings,
                )
            }
        },
    ) {
        val conversation = conversations.firstOrNull { it.id == selectedId } ?: conversations.first()
        ChatWorkspace(
            conversation = conversation,
            appCatalog = catalog,
            settings = settings,
            openDrawer = { scope.launch { drawerState.open() } },
            updateConversation = { updated -> persist(conversations.map { if (it.id == updated.id) updated else it }) },
        )
    }

    availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { if (downloadProgress == null) availableUpdate = null },
            title = { Text("发现新版本 ${update.version}") },
            text = {
                val progress = downloadProgress
                if (progress == null) {
                    Text(update.notes.ifBlank { "可以从 GitHub Release 下载并安装新版本。" })
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("正在下载… ${progress.percent}%")
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${formatMegabytes(progress.downloadedBytes)} / ${if (progress.totalBytes > 0) formatMegabytes(progress.totalBytes) else "未知大小"}", color = Muted)
                    }
                }
            },
            confirmButton = {
                if (downloadProgress == null) Button(onClick = {
                    updateJob = scope.launch {
                        updateMessage = "正在下载更新…"
                        runCatching { updater.downloadAndInstall(update) { downloadProgress = it } }
                            .onFailure { updateMessage = "更新失败：${it.message}" }
                        downloadProgress = null
                        availableUpdate = null
                    }
                }) { Text("下载并安装") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (downloadProgress != null) updateJob?.cancel()
                    downloadProgress = null
                    availableUpdate = null
                }) { Text(if (downloadProgress == null) "稍后" else "取消下载") }
            },
        )
    }
    updateMessage?.let { message ->
        if (message.startsWith("更新失败") || message.startsWith("更新检查失败")) {
            AlertDialog(
                onDismissRequest = { updateMessage = null },
                title = { Text("更新提示") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { updateMessage = null }) { Text("知道了") } },
            )
        }
    }
}

@Composable
private fun DrawerContent(
    conversations: List<Conversation>,
    selectedId: String,
    apiKey: String,
    activeProvider: String,
    defaultPackage: String,
    githubRepository: String,
    modelBaseUrl: String,
    modelName: String,
    visionEnabled: Boolean,
    visionApiKey: String,
    visionBaseUrl: String,
    visionModelName: String,
    appCount: Int,
    connected: Boolean,
    nextRunAt: Long,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onPin: (String) -> Unit,
    onDelete: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onDefaultPackage: (String) -> Unit,
    onGithubRepository: (String) -> Unit,
    onModelBaseUrl: (String) -> Unit,
    onModelName: (String) -> Unit,
    onVisionEnabled: (Boolean) -> Unit,
    onVisionApiKey: (String) -> Unit,
    onVisionBaseUrl: (String) -> Unit,
    onVisionModelName: (String) -> Unit,
    onModelPreset: (String) -> Unit,
    onCancelSchedule: () -> Unit,
    openAccessibilitySettings: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxHeight().padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("MUSE v${BuildConfig.VERSION_NAME}", color = Color(0xFF80DDA8), fontWeight = FontWeight.Bold)
                Text("私人平板助手", color = Color.White)
            }
            TextButton(onClick = onNew) { Text("＋ 新对话", color = Color.White) }
        }
        Spacer(Modifier.height(20.dp))
        Text("历史对话", color = Color(0xFF98A59D), style = MaterialTheme.typography.labelMedium)
        Column(
            Modifier.weight(0.35f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            conversations.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt }).forEach { chat ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (chat.id == selectedId) Color(0xFF29362E) else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onSelect(chat.id) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (chat.pinned) "●" else "○", color = if (chat.pinned) Color(0xFF80DDA8) else Color(0xFF66746B))
                    Text(chat.title, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1)
                    TextButton(onClick = { onPin(chat.id) }) {
                        Text(if (chat.pinned) "取消置顶" else "置顶", color = Color(0xFF9BA79F), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { pendingDelete = chat.id }) {
                        Text("删除", color = Color(0xFFDFA39F), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF354139))
        Column(
            Modifier.weight(0.65f).verticalScroll(rememberScrollState()).padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Agent 配置", color = Color.White, fontWeight = FontWeight.Bold)
            Text(if (connected) "● 无障碍已连接" else "○ 无障碍未连接", color = if (connected) Color(0xFF80DDA8) else Color(0xFFFFC36A))
            Text("已发现 $appCount 个可启动应用", color = Color(0xFFB9C2BC), style = MaterialTheme.typography.bodySmall)
            if (nextRunAt > System.currentTimeMillis()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("每日任务已排程", color = Color(0xFF80DDA8))
                        Text(formatScheduleTime(nextRunAt), color = Color(0xFFB9C2BC), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = onCancelSchedule) { Text("停用", color = Color(0xFFDFA39F)) }
                }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKey,
                label = { Text("${providerName(activeProvider)} API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onModelPreset("deepseek") }) { Text("DeepSeek", color = providerColor(activeProvider == "deepseek")) }
                TextButton(onClick = { onModelPreset("qwen") }) { Text("Qwen", color = providerColor(activeProvider == "qwen")) }
                TextButton(onClick = { onModelPreset("mimo") }) { Text("MiMo", color = providerColor(activeProvider == "mimo")) }
            }
            OutlinedTextField(
                value = modelName,
                onValueChange = onModelName,
                label = { Text("模型名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = modelBaseUrl,
                onValueChange = onModelBaseUrl,
                label = { Text("OpenAI-compatible Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("视觉规划", color = Color.White)
                    Text("仅启用时发送当前屏幕截图", color = Color(0xFFB9C2BC), style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = visionEnabled, onCheckedChange = onVisionEnabled)
            }
            if (visionEnabled) {
                OutlinedTextField(
                    value = visionApiKey,
                    onValueChange = onVisionApiKey,
                    label = { Text("视觉模型 API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = visionModelName,
                    onValueChange = onVisionModelName,
                    label = { Text("视觉模型名称") },
                    placeholder = { Text("qwen3-vl-flash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = visionBaseUrl,
                    onValueChange = onVisionBaseUrl,
                    label = { Text("视觉模型 Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = defaultPackage,
                onValueChange = onDefaultPackage,
                label = { Text("默认应用（可留空）") },
                placeholder = { Text("留空时自动识别") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = githubRepository,
                onValueChange = onGithubRepository,
                label = { Text("更新仓库") },
                placeholder = { Text("owner/AndroidAgent") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = openAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                Text("打开无障碍设置", color = Color.White)
            }
        }
    }
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这段对话？") },
            text = { Text("此操作会删除本机保存的对话记录。") },
            confirmButton = {
                TextButton(onClick = { onDelete(id); pendingDelete = null }) { Text("删除", color = Color(0xFF9B2C2C)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ChatWorkspace(
    conversation: Conversation,
    appCatalog: AppCatalog,
    settings: SecureSettings,
    openDrawer: () -> Unit,
    updateConversation: (Conversation) -> Unit,
) {
    val context = LocalContext.current
    val state by AgentController.state.collectAsState()
    var input by remember(conversation.id) { mutableStateOf("") }
    var sending by remember(conversation.id) { mutableStateOf(false) }
    var showTrace by remember(conversation.id) { mutableStateOf(false) }
    var ownsActiveRun by remember(conversation.id) { mutableStateOf(false) }
    val interactionScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(conversation.messages.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    LaunchedEffect(state.running, state.outcome, conversation.id) {
        if (ownsActiveRun && !state.running && state.outcome.isNotBlank()) {
            val prefix = when {
                state.status.startsWith("Succeeded") -> "执行完成"
                state.status == "Stopped" || state.status == "Cancelled" -> "执行已停止"
                else -> "执行未完成"
            }
            updateConversation(
                conversation.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = conversation.messages + ChatMessage("assistant", "$prefix：${state.outcome}"),
                ),
            )
            ownsActiveRun = false
        }
    }

    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = openDrawer) { Text("☰", color = Ink, style = MaterialTheme.typography.headlineSmall) }
                Column {
                    Text(conversation.title, color = Ink, fontWeight = FontWeight.Bold)
                    Text("${if (state.accessibilityConnected) "● 已连接" else "○ 未连接"} · ${translateStatus(state.status)}", color = if (state.accessibilityConnected) Accent else Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            AnimatedVisibility(state.running) {
                Button(onClick = AgentController::stop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9B2C2C))) { Text("停止") }
            }
        }
        HorizontalDivider(color = Line)
        RunStatusPanel(
            state = state,
            onShowTrace = { showTrace = true },
        )

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (conversation.messages.isEmpty()) {
                Spacer(Modifier.height(60.dp))
                Text("想让平板做什么？", style = MaterialTheme.typography.headlineMedium, color = Ink, fontWeight = FontWeight.Bold)
                Text("直接说出应用名称和任务。输入 /list 查看已发现的应用。", color = Muted)
            }
            conversation.messages.forEach { message -> MessageBubble(message) }
        }

        Column(Modifier.fillMaxWidth().background(Color.White).padding(14.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("聊天或交代任务；/chat 只聊天，/run 强制执行，/trace 看轨迹") },
                minLines = 2,
                maxLines = 5,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (sending) "正在理解你的意思…" else "自动判断聊天或操作 · 本地安全校验", color = Muted, style = MaterialTheme.typography.labelSmall)
                Button(
                    enabled = input.isNotBlank() && !state.running && !sending,
                    onClick = {
                        val text = input.trim()
                        val title = if (conversation.messages.isEmpty()) text.removePrefix("/list").ifBlank { "应用列表" }.take(18) else conversation.title
                        val withUser = conversation.copy(
                            title = title,
                            updatedAt = System.currentTimeMillis(),
                            messages = conversation.messages + ChatMessage("user", text),
                        )
                        updateConversation(withUser)
                        input = ""
                        if (text.equals("/list", true)) {
                            val response = "已发现 ${appCatalog.list().size} 个可启动应用：\n\n" + appCatalog.list().joinToString("\n") { "• ${it.label}  (${it.packageName})" }
                            updateConversation(withUser.copy(messages = withUser.messages + ChatMessage("assistant", response)))
                        } else if (text.equals("/trace", true)) {
                            val response = AgentTraceStore(context).latestRunSummary()
                            updateConversation(withUser.copy(messages = withUser.messages + ChatMessage("assistant", response)))
                        } else if (ScheduleCommandParser.isCommand(text)) {
                            val request = ScheduleCommandParser.parse(text)
                            val response = if (request == null) {
                                "Usage: /schedule <triggerAtMillis>|<goal>"
                            } else {
                                ScheduledTaskScheduler.schedule(context, request)
                                "Scheduled task ${request.taskId} for ${request.triggerAtMillis}"
                            }
                            updateConversation(withUser.copy(messages = withUser.messages + ChatMessage("assistant", response)))
                        } else if (settings.apiKey.isBlank()) {
                            updateConversation(withUser.copy(messages = withUser.messages + ChatMessage("assistant", "请先在侧栏配置当前模型的 API Key。")))
                        } else {
                            sending = true
                            interactionScope.launch {
                                val history = conversation.messages.map { it.role to it.content }
                                val result = runCatching { DeepSeekClient().route(settings.apiKey, settings.modelBaseUrl, settings.modelName, text, appCatalog.compactList(), history, settings.currentProvider) }
                                result.onSuccess { decision ->
                                    val response = when (decision) {
                                        is InteractionDecision.Chat -> decision.reply
                                        is InteractionDecision.Action -> {
                                            if (!state.accessibilityConnected) {
                                                "这个请求需要操作设备，请先在侧栏开启并连接 Muse 无障碍服务。"
                                            } else {
                                                // Preserve the immutable user wording; the router may summarize but must not mutate locked entities.
                                                settings.taskGoal = if (text.startsWith("/run ", ignoreCase = true)) {
                                                    text.substringAfter(' ').trim()
                                                } else {
                                                    text
                                                }
                                                ownsActiveRun = true
                                                AgentController.start(context, settings)
                                                decision.reply
                                            }
                                        }
                                    }
                                    updateConversation(withUser.copy(
                                        updatedAt = System.currentTimeMillis(),
                                        messages = withUser.messages + ChatMessage("assistant", response),
                                    ))
                                }.onFailure { error ->
                                    updateConversation(withUser.copy(messages = withUser.messages + ChatMessage("assistant", "我暂时没理解成功：${error.message}")))
                                }
                                sending = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { Text("发送") }
            }
        }
    }

    if (showTrace) {
        AlertDialog(
            onDismissRequest = { showTrace = false },
            title = { Text("最近一次运行轨迹") },
            text = {
                Text(
                    AgentTraceStore(context).latestRunSummary(),
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showTrace = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun RunStatusPanel(state: AgentUiState, onShowTrace: () -> Unit) {
    AnimatedVisibility(state.running || state.outcome.isNotBlank()) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFFEEF3EE)).padding(horizontal = 22.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.running) "运行中 · ${translateStatus(state.status)}" else translateStatus(state.status),
                        color = if (state.status == "Failed") Color(0xFF9B2C2C) else Accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("第 ${state.step.coerceAtLeast(0)} / ${state.maxSteps} 步", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
                if (state.running) {
                    val progress by animateFloatAsState(
                        targetValue = (state.step.toFloat() / state.maxSteps.coerceAtLeast(1)).coerceIn(0f, 1f),
                        label = "run-progress",
                    )
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Accent)
                }
                if (state.goal.isNotBlank()) Text(state.goal, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 2)
                if (state.currentAction.isNotBlank()) Text("→ ${state.currentAction}", color = Accent)
                state.logs.take(3).reversed().forEach { log ->
                    Text(log, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (!state.running && state.outcome.isNotBlank()) {
                    Text(state.outcome, color = if (state.status.startsWith("Succeeded")) Accent else Color(0xFF9B2C2C), maxLines = 3)
                }
                TextButton(onClick = onShowTrace, modifier = Modifier.align(Alignment.End)) { Text("查看完整轨迹") }
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(if (message.role == "user") .82f else .92f)
                .background(if (message.role == "user") Ink else Color(0xFFE9EEE9), RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            Text(if (message.role == "user") "你" else "Agent", color = if (message.role == "user") Color(0xFF92DDB2) else Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(5.dp))
            Text(message.content, color = if (message.role == "user") Color.White else Ink)
        }
    }
}

private fun translateStatus(status: String): String = when {
    status == "Idle" -> "空闲"
    status == "Preparing" -> "准备中"
    status == "Compiling" -> "拆解任务"
    status == "Observing" -> "读取页面"
    status == "Planning" -> "正在规划"
    status == "Acting" -> "正在操作"
    status == "Critiquing" -> "检查结果"
    status == "Verifying" -> "最终验收"
    status == "Replanning" -> "更换策略"
    status == "Stopped" -> "已停止"
    status == "Cancelled" -> "已取消"
    status == "Failed" -> "执行失败"
    status.startsWith("Succeeded") -> "已完成"
    else -> status
}

private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)

private fun formatScheduleTime(timestamp: Long): String =
    "下次执行：${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))}"

private fun providerColor(selected: Boolean): Color = if (selected) Color(0xFF80DDA8) else Color.White

private fun providerName(provider: String): String = when (provider) {
    "qwen" -> "Qwen"
    "mimo" -> "MiMo"
    else -> "DeepSeek"
}
