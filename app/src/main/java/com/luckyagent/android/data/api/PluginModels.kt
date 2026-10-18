package com.luckyagent.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InstalledPlugin(
    val name: String,
    val version: String = "",
    val description: String = "",
    val source: String = "",
    val ref: String = "",
    val revision: String = "",
    val subdir: String = "",
    val digest: String = "",
    @SerialName("installed_at") val installedAt: String = "",
    val enabled: Boolean = false,
    val trusted: Boolean = false,
    @SerialName("requires_trust") val requiresTrust: Boolean = false,
    val skills: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    @SerialName("mcp_servers") val mcpServers: List<String> = emptyList(),
    @SerialName("hook_count") val hookCount: Int = 0,
    val error: String? = null,
)

@Serializable
data class PluginListResponse(
    val plugins: List<InstalledPlugin> = emptyList(),
    val count: Int = 0,
    @SerialName("changes_require_restart") val changesRequireRestart: Boolean = true,
)

@Serializable
data class PluginInstallRequest(
    val source: String,
    val ref: String = "",
    val subdir: String = "",
    val trust: Boolean = false,
)

@Serializable
data class PluginUpdateRequest(val trust: Boolean = false)

@Serializable
data class PluginChangeResponse(
    val action: String,
    val name: String,
    val plugin: InstalledPlugin? = null,
    @SerialName("runtime_restart_required") val runtimeRestartRequired: Boolean = true,
    val message: String = "",
)
