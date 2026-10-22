package com.luckyagent.android.data.standalone

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

enum class ConnectionMode { Runtime, Standalone }

@Serializable
data class StandaloneSettings(
    val apiBase: String = "",
    val apiKey: String = "",
    val model: String = "",
    val sessionId: String = "standalone-main",
)

@Serializable
data class StandaloneMessage(
    val role: String,
    val content: String,
    val kind: String = "chat",
)

@Serializable
data class StandaloneSession(
    val id: String,
    val title: String = "",
    val messages: List<StandaloneMessage> = emptyList(),
    val summary: String = "",
    val updatedAt: Long = 0L,
)

/** Rough token estimate. Chinese characters count as one token, Latin words as one. */
internal fun estimateTokens(text: String): Int {
    if (text.isBlank()) return 0
    var tokens = 0
    var latin = false
    text.forEach { ch ->
        if (ch.isWhitespace()) {
            if (latin) tokens += 1
            latin = false
        } else if (ch.code < 128) {
            latin = true
        } else {
            if (latin) tokens += 1
            latin = false
            tokens += 1
        }
    }
    if (latin) tokens += 1
    return tokens
}

internal fun standaloneContextTokens(session: StandaloneSession): Int =
    estimateTokens(session.summary) + session.messages.sumOf { estimateTokens(it.content) }

/**
 * Keep the newest messages and fold the older ones into one summary.
 * Returns null when there is nothing old enough to fold.
 */
internal fun compactStandaloneSession(session: StandaloneSession, keepRecentTokens: Int = 1200): StandaloneSession? {
    if (session.messages.size < 4) return null
    var kept = 0
    var cut = session.messages.size
    for (index in session.messages.indices.reversed()) {
        kept += estimateTokens(session.messages[index].content)
        if (kept > keepRecentTokens && index < session.messages.size - 2) {
            cut = index
            break
        }
        cut = index
    }
    if (cut <= 0) return null
    val older = session.messages.take(cut)
    val folded = buildString {
        if (session.summary.isNotBlank()) append(session.summary.trim()).append("\n")
        older.forEach { message ->
            append(if (message.role == "user") "用户" else "助手")
            append("：")
            append(message.content.trim().take(240))
            append('\n')
        }
    }.trim().take(4000)
    if (folded.isBlank()) return null
    return session.copy(summary = folded, messages = session.messages.drop(cut))
}

internal fun buildStandaloneMessages(session: StandaloneSession, userText: String): List<Map<String, String>> {
    val system = buildString {
        append("你是手机上的独立聊天助手。回答使用用户所用的语言。")
        if (session.summary.isNotBlank()) {
            append("\n以下是更早对话的压缩摘要，继续遵守其中的约束：\n")
            append(session.summary)
        }
    }
    return buildList {
        add(mapOf("role" to "system", "content" to system))
        session.messages.filter { it.kind == "chat" }.forEach { message ->
            add(mapOf("role" to message.role, "content" to message.content))
        }
        add(mapOf("role" to "user", "content" to userText))
    }
}

class StandaloneChatClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun check(settings: StandaloneSettings): Result<String> = runCatching {
        val model = settings.model.ifBlank { "model" }
        complete(settings, listOf(mapOf("role" to "user", "content" to "Reply with ok")), maxTokens = 8)
            .getOrThrow()
        model
    }

    fun complete(
        settings: StandaloneSettings,
        messages: List<Map<String, String>>,
        maxTokens: Int = 1024,
    ): Result<String> = runCatching {
        require(settings.apiBase.isNotBlank()) { "模型 URL 还没填" }
        require(settings.apiKey.isNotBlank()) { "模型 Key 还没填" }
        val payload = json.encodeToString(
            mapOf(
                "model" to settings.model.ifBlank { "gpt-4o-mini" },
                "messages" to messages,
                "max_tokens" to maxTokens,
            ),
        )
        val request = Request.Builder()
            .url(chatCompletionsUrl(settings.apiBase))
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(readableModelError(response.code, body))
            extractCompletion(body)
        }
    }

    fun search(query: String): Result<String> = runCatching {
        val trimmed = query.trim()
        require(trimmed.isNotBlank()) { "搜索词是空的" }
        val request = Request.Builder()
            .url("https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}&format=json&no_html=1&skip_disambig=1")
            .header("User-Agent", "LuckyAgent-Android")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("搜索失败：${response.code}")
            formatSearch(trimmed, body).ifBlank { "没有搜到「$trimmed」的结果" }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

internal fun chatCompletionsUrl(apiBase: String): String {
    val base = apiBase.trim().trimEnd('/')
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
}

internal fun extractCompletion(body: String): String {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(body)
    val content = root.jsonObject["choices"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("message")
        ?.jsonObject
        ?.get("content")
        ?.jsonPrimitive
        ?.content
        ?.trim()
        .orEmpty()
    if (content.isBlank()) error("模型没有返回正文")
    return content
}

internal fun formatSearch(query: String, body: String): String {
    val root = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return ""
    val lines = mutableListOf<String>()
    root["AbstractText"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { lines += it }
    root["AbstractURL"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { lines += it }
    root["RelatedTopics"]?.jsonArray?.take(5)?.forEach { item ->
        val obj = item.jsonObject
        val text = obj["Text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val url = obj["FirstURL"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (text != null) lines += if (url != null) "$text\n$url" else text
    }
    if (lines.isEmpty()) return ""
    return "搜索「$query」\n" + lines.joinToString("\n")
}

internal fun readableModelError(code: Int, body: String): String {
    val message = runCatching {
        Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject["error"]
            ?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
    return message ?: "模型请求失败：$code"
}
