package com.luckyagent.android.ui.util

import java.net.URLEncoder

/** Canonical artifact route shared by attachment downloads and previews. */
internal fun artifactDownloadUrl(path: String, apiBase: String): String? {
    if (apiBase.isBlank()) return null
    val normalized = path.replace('\\', '/')
    val relative = when {
        normalized.startsWith("~/.luckyagent/workspace/") -> "workspace/" + normalized.removePrefix("~/.luckyagent/workspace/")
        normalized.startsWith("~/.luckyagent/uploads/") -> "uploads/" + normalized.removePrefix("~/.luckyagent/uploads/")
        normalized.contains("/.luckyagent/workspace/") -> "workspace/" + normalized.substringAfter("/.luckyagent/workspace/")
        normalized.contains("/.luckyagent/uploads/") -> "uploads/" + normalized.substringAfter("/.luckyagent/uploads/")
        else -> return null
    }
    if (relative.substringAfterLast('/').isBlank()) return null
    return apiBase.trimEnd('/') + "/api/v1/artifacts?path=" + URLEncoder.encode(relative, Charsets.UTF_8.name())
}
