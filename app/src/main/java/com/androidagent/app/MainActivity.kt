package com.androidagent.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.androidagent.app.apps.AppCatalog
import com.androidagent.app.chat.ChatMessage
import com.androidagent.app.chat.ChatStore
import com.androidagent.app.chat.Conversation
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.network.DeepSeekClient
import com.androidagent.app.network.InteractionDecision
import com.androidagent.app.update.GitHubUpdater
import com.androidagent.app.update.UpdateInfo
import kotlinx.coroutines.launch

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
    var conversations by remember {
        mutableStateOf(chatStore.load().ifEmpty { listOf(Conversation()) })
    }
    var selectedId by remember { mutableStateOf(conversations.first().id) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var defaultPackage by remember { mutableStateOf(settings.targetPackage) }
    var githubRepository by remember { mutableStateOf(settings.githubRepository) }
    var modelBaseUrl by remember { mutableStateOf(settings.modelBaseUrl) }
    var modelName by remember { mutableStateOf(settings.modelName) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    val apps = remember { catalog.list() }
    val updater = remember { GitHubUpdater(context) }

    LaunchedEffect(githubRepository) {
        if (githubRepository.isNotBlank()) {
            runCatching { updater.check(githubRepository) }
                .onSuccess { availableUpdate = it }
                .onFailure { updateMessage = "更新检查失败：${it.message}" }
        }
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
                    defaultPackage = defaultPackage,
                    githubRepository = githubRepository,
                    modelBaseUrl = modelBaseUrl,
                    modelName = modelName,
                    appCount = apps.size,
                    connected = AgentController.state.value.accessibilityConnected,
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
                    onModelPreset = { preset ->
                        val values = when (preset) {
                            "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1" to "qwen-plus"
                            "mimo" -> "https://dashscope.aliyuncs.com/compatible-mode/v1" to "mimo-v2.5-pro"
                            else -> "https://api.deepseek.com" to "deepseek-v4-flash"
                        }
                        modelBaseUrl = values.first
                        modelName = values.second
                        settings.modelBaseUrl = values.first
                        settings.modelName = values.second
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
            onDismissRequest = { availableUpdate = null },
            title = { Text("发现新版本 ${update.version}") },
            text = { Text(update.notes.ifBlank { "可以从 GitHub Release 下载并安装新版本。" }) },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        updateMessage = "正在下载更新…"
                        runCatching { updater.downloadAndInstall(update) }
                            .onFailure { updateMessage = "更新失败：${it.message}" }
                        availableUpdate = null
                    }
                }) { Text("下载并安装") }
            },
            dismissButton = { TextButton(onClick = { availableUpdate = null }) { Text("稍后") } },
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
    defaultPackage: String,
    githubRepository: String,
    modelBaseUrl: String,
    modelName: String,
    appCount: Int,
    connected: Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onPin: (String) -> Unit,
    onDelete: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onDefaultPackage: (String) -> Unit,
    onGithubRepository: (String) -> Unit,
    onModelBaseUrl: (String) -> Unit,
    onModelName: (String) -> Unit,
    onModelPreset: (String) -> Unit,
    openAccessibilitySettings: () -> Unit,
) {
    Column(Modifier.fillMaxHeight().padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("ANDROID AGENT", color = Color(0xFF80DDA8), fontWeight = FontWeight.Bold)
                Text("私人平板助手", color = Color.White)
            }
            TextButton(onClick = onNew) { Text("＋ 新对话", color = Color.White) }
        }
        Spacer(Modifier.height(20.dp))
        Text("历史对话", color = Color(0xFF98A59D), style = MaterialTheme.typography.labelMedium)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
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
                    Text("置顶", color = Color(0xFF9BA79F), modifier = Modifier.clickable { onPin(chat.id) }.padding(4.dp), style = MaterialTheme.typography.labelSmall)
                    Text("删", color = Color(0xFFDFA39F), modifier = Modifier.clickable { onDelete(chat.id) }.padding(4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        HorizontalDivider(color = Color(0xFF354139))
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Agent 配置", color = Color.White, fontWeight = FontWeight.Bold)
            Text(if (connected) "● 无障碍已连接" else "○ 无障碍未连接", color = if (connected) Color(0xFF80DDA8) else Color(0xFFFFC36A))
            Text("已发现 $appCount 个可启动应用", color = Color(0xFFB9C2BC), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKey,
                label = { Text("DeepSeek API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onModelPreset("deepseek") }) { Text("DeepSeek", color = Color(0xFF80DDA8)) }
                TextButton(onClick = { onModelPreset("qwen") }) { Text("Qwen", color = Color.White) }
                TextButton(onClick = { onModelPreset("mimo") }) { Text("MiMo", color = Color.White) }
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
    val interactionScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(conversation.messages.size, state.logs.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
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
            if (state.running || state.logs.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Agent · 第 ${state.step} 步 · ${translateStatus(state.status)}", color = Accent, fontWeight = FontWeight.Bold)
                    state.logs.take(4).reversed().forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(Color.White).padding(14.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("聊天或交代任务；/chat 只聊天，/run 强制执行") },
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
                        } else if (settings.apiKey.isBlank()) {
                            updateConversation(withUser.copy(messages = withUser.messages + ChatMessage("assistant", "请先在侧栏配置 DeepSeek API Key。")))
                        } else {
                            sending = true
                            interactionScope.launch {
                                val result = runCatching { DeepSeekClient().route(settings.apiKey, settings.modelBaseUrl, settings.modelName, text, appCatalog.compactList()) }
                                result.onSuccess { decision ->
                                    val response = when (decision) {
                                        is InteractionDecision.Chat -> decision.reply
                                        is InteractionDecision.Action -> {
                                            settings.taskGoal = decision.goal
                                            AgentController.start(context, settings)
                                            decision.reply
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
    status == "Observing" -> "读取页面"
    status == "Planning" -> "正在规划"
    status == "Acting" -> "正在操作"
    status == "Stopped" -> "已停止"
    status == "Cancelled" -> "已取消"
    status == "Failed" -> "执行失败"
    status.startsWith("Succeeded") -> "已完成"
    else -> status
}
