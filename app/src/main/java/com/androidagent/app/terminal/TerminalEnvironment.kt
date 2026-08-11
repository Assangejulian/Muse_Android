package com.androidagent.app.terminal

import com.androidagent.app.data.SecureSettings
import com.androidagent.app.privileged.PrivilegedBackendRouter

data class TerminalTool(
    val id: String,
    val label: String,
    val defaultCommand: String,
)

data class TerminalEnvironmentConfig(
    val workingDirectory: String,
    val pathPrefix: String,
    val enabledTools: Set<String>,
    val commands: Map<String, String>,
) {
    fun wrap(command: String): String {
        val safeDirectory = shellQuote(validatePath(workingDirectory, SecureSettings.DEFAULT_WORKING_DIRECTORY))
        val prefix = pathPrefix.trim().takeIf(::isSafePath)
        val pathClause = if (prefix == null) "" else "PATH=${shellQuote(prefix)}:\$PATH; export PATH; "
        return "cd $safeDirectory 2>/dev/null || cd /sdcard; $pathClause${command.trim()}"
    }

    fun promptSummary(status: Map<String, String> = emptyMap()): String = TERMINAL_TOOLS
        .filter { it.id in enabledTools }
        .joinToString("\n") { tool ->
            val command = commands[tool.id].orEmpty().ifBlank { tool.defaultCommand }
            val detected = status[tool.id]?.let { "available at $it" } ?: "not yet detected"
            "- ${tool.label}: command=$command, $detected"
        }

    companion object {
        fun from(settings: SecureSettings): TerminalEnvironmentConfig = TerminalEnvironmentConfig(
            workingDirectory = settings.terminalWorkingDirectory,
            pathPrefix = settings.terminalPathPrefix,
            enabledTools = settings.enabledTerminalTools,
            commands = TERMINAL_TOOLS.associate { tool ->
                tool.id to settings.terminalToolCommand(tool.id, tool.defaultCommand)
            },
        )
    }
}

object TerminalEnvironmentProbe {
    suspend fun probe(config: TerminalEnvironmentConfig): Map<String, String> {
        val enabled = TERMINAL_TOOLS.filter { it.id in config.enabledTools }
        if (enabled.isEmpty()) return emptyMap()
        val checks = enabled.joinToString("; ") { tool ->
            val command = config.commands[tool.id].orEmpty().ifBlank { tool.defaultCommand }
            val safeCommand = command.takeIf(::isSafeExecutable) ?: tool.defaultCommand
            "resolved=\$(command -v ${shellQuote(safeCommand)} 2>/dev/null || true); " +
                "printf '${tool.id}=%s\\n' \"\$resolved\""
        }
        val result = PrivilegedBackendRouter.execute(config.wrap(checks), 8_000L)
        if (!result.success) return emptyMap()
        return result.stdout.lineSequence()
            .mapNotNull { line ->
                val id = line.substringBefore('=', "").trim()
                val path = line.substringAfter('=', "").trim()
                if (id.isNotBlank() && path.isNotBlank()) id to path else null
            }
            .toMap()
    }
}

val TERMINAL_TOOLS = listOf(
    TerminalTool("shell", "Shell", "sh"),
    TerminalTool("ssh", "SSH", "ssh"),
    TerminalTool("python", "Python", "python3"),
    TerminalTool("node", "Node.js", "node"),
    TerminalTool("java", "Java", "java"),
)

internal fun isSafeExecutable(value: String): Boolean =
    value.isNotBlank() && value.length <= 240 && SAFE_EXECUTABLE.matches(value)

internal fun isSafePath(value: String): Boolean =
    value.startsWith('/') && value.length <= 512 && SAFE_PATH.matches(value)

private fun validatePath(value: String, fallback: String): String = value.trim().takeIf(::isSafePath) ?: fallback

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

private val SAFE_EXECUTABLE = Regex("[A-Za-z0-9_./+:-]+")
private val SAFE_PATH = Regex("/[A-Za-z0-9_./+ -]*")
