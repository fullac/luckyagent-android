package com.luckyagent.android.data.standalone

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class StandaloneStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "standalone").apply { mkdirs() }

    fun load(sessionId: String): StandaloneSession {
        val file = file(sessionId)
        if (!file.exists()) return StandaloneSession(id = sessionId)
        return runCatching { json.decodeFromString(StandaloneSession.serializer(), file.readText()) }
            .getOrDefault(StandaloneSession(id = sessionId))
    }

    fun save(session: StandaloneSession) {
        file(session.id).writeText(json.encodeToString(session.copy(updatedAt = System.currentTimeMillis())))
    }

    private fun file(sessionId: String): File {
        val safe = sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "standalone-main" }
        return File(dir, "$safe.json")
    }
}
