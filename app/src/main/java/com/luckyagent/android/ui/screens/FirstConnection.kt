package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.settings.ClientSettings

internal data class ManualConnection(
    val apiBase: String,
    val apiKey: String,
    val useBearer: Boolean,
)

/**
 * The first-run gate stays up until a computer address and key exist and the
 * latest health check has not failed. A later socket reconnect is separate.
 * Standalone model chat does not use this computer connection.
 */
internal fun needsConnectionSetup(settings: ClientSettings, healthOk: Boolean?): Boolean {
    if (settings.connectionMode == "standalone") return false
    if (healthOk == false) return true
    return settings.apiBase.isBlank() || settings.apiKey.isBlank()
}

/** Accepts an address and key. A leading "Bearer " selects that header; otherwise the key is sent as X-API-Key. */
internal fun manualConnection(rawBase: String, rawKey: String): ManualConnection? {
    val base = rawBase.trim().trimEnd('/')
    if (!base.startsWith("http://") && !base.startsWith("https://")) return null
    val raw = rawKey.trim()
    val bearer = raw.startsWith("Bearer ", ignoreCase = true)
    val key = if (bearer) raw.substringAfter(' ').trim() else raw
    if (key.isBlank()) return null
    return ManualConnection(base, key, bearer)
}

internal fun connectionHostLabel(apiBase: String, endpointName: String? = null): String {
    val name = endpointName?.trim()?.substringBefore(" ·")?.takeIf { label ->
        label.isNotBlank() && !label.equals("Default", ignoreCase = true) && !label.equals("Runtime", ignoreCase = true)
    }
    if (name != null) return name
    val withoutScheme = apiBase.trim().substringAfter("://", apiBase.trim())
    return withoutScheme.substringBefore('/').substringBefore(':').ifBlank { "这台电脑" }
}
