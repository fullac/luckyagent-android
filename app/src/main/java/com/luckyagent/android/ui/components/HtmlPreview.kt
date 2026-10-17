package com.luckyagent.android.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val DOCUMENT_LIMIT = 2 * 1024 * 1024
private const val PREVIEW_ORIGIN = "https://html-preview.luckyagent.invalid/"

internal fun htmlResourceBase(documentUrl: String): String {
    val url = documentUrl.toHttpUrlOrNull() ?: return documentUrl
    if (!url.encodedPath.endsWith("/api/v1/artifacts")) return documentUrl
    val path = url.queryParameter("path") ?: return documentUrl
    return PREVIEW_ORIGIN.toHttpUrlOrNull()!!.newBuilder().addPathSegment("artifact").apply {
        path.split('/').forEach { addPathSegment(it) }
    }.build().toString()
}

internal fun htmlResourceUrl(requestUrl: String, documentUrl: String): String? {
    val request = requestUrl.toHttpUrlOrNull() ?: return null
    if (request.host != "html-preview.luckyagent.invalid") return request.toString()
    val document = documentUrl.toHttpUrlOrNull() ?: return null
    if (!document.encodedPath.endsWith("/api/v1/artifacts") || request.pathSegments.firstOrNull() != "artifact") return null
    return document.newBuilder().setQueryParameter("path", request.pathSegments.drop(1).joinToString("/")).build().toString()
}

/** Only GET resources receive credentials, and only for the configured API origin, including redirects. */
internal class HtmlResourceLoader(private val headers: Map<String, String>, private val authBaseUrl: String) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    data class Resource(val bytes: ByteArray, val mimeType: String, val charset: String, val url: String)

    fun fetch(source: String, limit: Int = DOCUMENT_LIMIT): Resource {
        var url = resolveHtmlUrl(source, authBaseUrl) ?: throw IOException("地址不是有效的 HTTP/HTTPS 地址")
        repeat(6) {
            val request = Request.Builder().url(url).get().apply {
                if (sameHtmlOrigin(url, authBaseUrl)) headers.forEach { (name, value) -> header(name, value) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    url = response.header("Location")?.let { resolveHtmlUrl(it, url) }
                        ?: throw IOException("重定向地址无效")
                } else {
                    if (!response.isSuccessful) throw IOException("服务器返回 ${response.code}")
                    val body = response.body ?: throw IOException("文件内容为空")
                    val type = body.contentType()
                    return Resource(body.byteStream().use { it.readLimited(limit) },
                        type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
                        type?.charset()?.name() ?: "UTF-8", url)
                }
            }
        }
        throw IOException("重定向次数过多")
    }
}

private fun InputStream.readLimited(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (out.size() + count > limit) throw IOException("内容超过 ${limit / 1024 / 1024} MB 的预览上限，请下载查看")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

internal fun htmlPreviewDocument(source: String, resourceBaseUrl: String): String {
    val doc = Jsoup.parse(source)
    // The document has an isolated origin; the base only resolves CSS/images and page anchors.
    doc.select("base").remove()
    resolveHtmlUrl(resourceBaseUrl, resourceBaseUrl)?.let { doc.head().prependElement("base").attr("href", htmlResourceBase(it)) }
    if (doc.selectFirst("meta[name=viewport]") == null) {
        doc.head().prependElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
    }
    doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
        "default-src https: http: data: blob: 'unsafe-inline' 'unsafe-eval'; connect-src 'none'; form-action 'none'; object-src 'none'")
    doc.head().appendElement("style").appendText("html{overflow-wrap:anywhere}img,video{max-width:100%;height:auto}pre{overflow:auto}")
    return doc.outerHtml()
}

@Composable
internal fun HtmlPreviewCard(source: String, baseUrl: String, headers: Map<String, String>) {
    var show by remember { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { show = true }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("HTML", style = MaterialTheme.typography.labelLarge)
            Text("打开预览 ›", color = MaterialTheme.colorScheme.primary)
        }
    }
    if (show) HtmlPreviewDialog(source = source, baseUrl = baseUrl, headers = headers, onDismiss = { show = false })
}

/** One full-page viewer used by both fenced HTML and file attachments. */
@Composable
internal fun HtmlPreviewDialog(
    source: String? = null,
    uri: String? = null,
    title: String = "HTML 预览",
    baseUrl: String = "",
    headers: Map<String, String> = emptyMap(),
    onDownload: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember(headers, baseUrl) { HtmlResourceLoader(headers, baseUrl) }
    var attempt by remember { mutableIntStateOf(0) }
    var content by remember(source, uri) { mutableStateOf(source) }
    var resourceBase by remember(uri, baseUrl) { mutableStateOf(resolveHtmlUrl(uri.orEmpty(), baseUrl) ?: baseUrl) }
    var error by remember(source, uri) { mutableStateOf<String?>(null) }
    var showSource by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { target ->
        if (target != null) scope.launch {
            saved = runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(target)?.bufferedWriter()?.use { it.write(content.orEmpty()) }
                    ?: throw IOException("无法写入文件")
            }; "已保存 HTML" }.getOrElse { "保存失败：${it.message}" }
        }
    }
    LaunchedEffect(source, uri, loader, attempt) {
        if (source != null) return@LaunchedEffect
        error = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                val parsed = Uri.parse(uri ?: throw IOException("附件没有可预览的地址"))
                if (parsed.scheme == "content") {
                    val bytes = context.contentResolver.openInputStream(parsed)?.use { it.readLimited(DOCUMENT_LIMIT) }
                        ?: throw IOException("无法读取附件")
                    String(bytes, Charsets.UTF_8) to baseUrl
                } else {
                    val response = loader.fetch(uri.orEmpty())
                    String(response.bytes, charset(response.charset)) to response.url
                }
            }
            content = loaded.first
            resourceBase = loaded.second
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法加载 HTML" }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭 HTML 预览") }
                    Text(title, modifier = Modifier.weight(1f).padding(vertical = 14.dp), maxLines = 1)
                    IconButton(enabled = content != null || onDownload != null, onClick = {
                        if (onDownload != null) onDownload() else save.launch("LuckyAgent.html")
                    }) { Icon(Icons.Outlined.Download, "下载 HTML") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showSource, onClick = { showSource = false }, label = { Text("预览") })
                    FilterChip(selected = showSource, onClick = { showSource = true }, label = { Text("源码") })
                }
                saved?.let { Text(it, Modifier.padding(12.dp)) }
                when {
                    error != null -> Column(Modifier.padding(16.dp)) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { attempt++ }) { Text("重试") }
                    }
                    content == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                    else -> Box(Modifier.fillMaxWidth().weight(1f)) {
                        HtmlPageView(content.orEmpty(), resourceBase, loader, Modifier.fillMaxSize(), visible = !showSource)
                        if (showSource) Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                                if (content.orEmpty().length > 32_000) Text("源码较长，此处显示前 32,000 字符；下载可保存完整文件")
                                CodeText(content.orEmpty().take(32_000))
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPageView(source: String, baseUrl: String, loader: HtmlResourceLoader, modifier: Modifier, visible: Boolean) {
    val document = remember(source, baseUrl) { htmlPreviewDocument(source, baseUrl) }
    AndroidView(modifier = modifier, factory = { context -> WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // Local lh serve endpoints also serve HTTP CSS/images inside the isolated preview.
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (request.method != "GET" || resolveHtmlUrl(url, baseUrl) == null) return blockedHtmlResource()
                return try {
                    val target = htmlResourceUrl(url, baseUrl) ?: return blockedHtmlResource()
                    val resource = loader.fetch(target, limit = 8 * 1024 * 1024)
                    WebResourceResponse(resource.mimeType, resource.charset, ByteArrayInputStream(resource.bytes))
                } catch (_: Exception) { blockedHtmlResource() }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.substringBefore('#') == PREVIEW_ORIGIN.substringBefore('#')) return false
                if (request.url.fragment != null && url.substringBefore('#') == htmlResourceBase(baseUrl).substringBefore('#')) {
                    view.evaluateJavascript("location.hash=" + org.json.JSONObject.quote(request.url.fragment), null)
                } else if (request.hasGesture()) {
                    htmlResourceUrl(url, baseUrl)?.let { target ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    }
                }
                return true
            }
        }
    } }, update = { view ->
        view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
        if (view.tag != document) {
            view.tag = document
            view.loadDataWithBaseURL(PREVIEW_ORIGIN, document, "text/html", null, null)
        }
    }, onRelease = { it.stopLoading(); it.destroy() })
}

private fun blockedHtmlResource() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
