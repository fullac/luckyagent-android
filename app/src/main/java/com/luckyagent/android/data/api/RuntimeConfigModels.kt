package com.luckyagent.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

@Serializable
data class ModelEffortResponse(
    val model: String = "", val provider: String = "", val protocol: String = "",
    val effort: String = "auto", val options: List<String> = emptyList(),
    val supported: Boolean = false, val description: String? = null,
)

@Serializable
data class ModelEffortRequest(val effort: String)

@Serializable
data class RuntimeConfigSchemaField(
    val path: String, val type: String, val default: JsonElement? = null,
    val secret: Boolean = false, val apply: String = "unknown",
)

@Serializable
data class RuntimeConfigSchema(
    val version: Int = 0, val fields: List<RuntimeConfigSchemaField> = emptyList(),
    val groups: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class RuntimeConfigPatchEnvelope(
    val patch: JsonObject,
    @SerialName("clear_secrets") val clearSecrets: List<String> = emptyList(),
    @SerialName("if_match") val ifMatch: String? = null,
    @SerialName("dry_run") val dryRun: Boolean = false,
)

@Serializable
data class RuntimeConfigPatchResponse(
    val config: JsonObject? = null, val changed: List<String> = emptyList(),
    @SerialName("hot_reloaded") val hotReloaded: List<String> = emptyList(),
    @SerialName("restart_required") val restartRequired: List<String> = emptyList(),
    val applied: Boolean = false, val persisted: Boolean = false,
    @SerialName("dry_run") val dryRun: Boolean = false, val version: String = "",
)

data class RuntimeConfigSnapshot(val config: JsonObject, val etag: String?)
