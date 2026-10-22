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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.IOException

internal fun htmlPreviewDocument(source: String, resourceBaseUrl: String): String {
    val doc = Jsoup.parse(source)
    // The document has an isolated origin; the base only resolves CSS/images and page anchors.
    doc.select("base").remove()
    resolveHtmlUrl(resourceBaseUrl, resourceBaseUrl)?.let { doc.head().prependElement("base").attr("href", htmlResourceBase(it)) }
    if (doc.selectFirst("meta[name=viewport]") == null) {
        doc.head().prependElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
    }
    // Page JS may run for DOM/SVG interaction, but must not talk to the network.
    // Subresources are mediated by shouldInterceptRequest + htmlResourceUrl allowlist.
    doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
        "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' data:; " +
            "img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' data: blob:; " +
            "connect-src 'none'; worker-src 'none'; child-src 'none'; form-action 'none'; object-src 'none'; frame-src 'none'; base-uri 'self'")
    doc.head().appendElement("style").appendText("html,body{margin:0;background:transparent}html{overflow-wrap:anywhere}img,video{max-width:100%;height:auto}pre{overflow:auto}")
    return doc.outerHtml()
}

private const val INLINE_HTML_MAX_HEIGHT = 360

/**
 * Fenced HTML sits in the message flow. A tap opens the existing full-screen preview.
 * Attachments keep [HtmlPreviewDialog] and do not use this inline surface.
 */
@Composable
internal fun InlineHtmlPreview(source: String, baseUrl: String, headers: Map<String, String>) {
    var show by remember(source) { mutableStateOf(false) }
    var contentHeight by remember(source) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val height = if (contentHeight > 0) {
        with(density) { contentHeight.toDp() }.coerceIn(24.dp, INLINE_HTML_MAX_HEIGHT.dp)
    } else {
        48.dp
    }
    val loader = remember(headers, baseUrl, source) { HtmlResourceLoader(headers, baseUrl) }
    DisposableEffect(loader) { onDispose { loader.close() } }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clickable { show = true },
    ) {
        HtmlPageView(
            source = source,
            baseUrl = baseUrl,
            loader = loader,
            modifier = Modifier.fillMaxSize(),
            visible = true,
            zoomable = false,
            onContentHeight = { contentHeight = it },
        )
        // The WebView would otherwise take the list's vertical drags.
        Box(Modifier.fillMaxSize().clickable { show = true })
    }
    if (show) HtmlPreviewDialog(source = source, baseUrl = baseUrl, headers = headers, onDismiss = { show = false })
}

@Composable
internal fun HtmlPreviewCard(source: String, baseUrl: String, headers: Map<String, String>) {
    InlineHtmlPreview(source = source, baseUrl = baseUrl, headers = headers)
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
    var attempt by remember { mutableIntStateOf(0) }
    val loader = remember(headers, baseUrl, source, uri, attempt) { HtmlResourceLoader(headers, baseUrl) }
    DisposableEffect(loader) { onDispose { loader.close() } }
    var content by remember(source, uri) { mutableStateOf(source) }
    var resourceBase by remember(source, uri, baseUrl) { mutableStateOf(if (source != null) "" else resolveHtmlUrl(uri.orEmpty(), baseUrl) ?: "") }
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
                    val bytes = context.contentResolver.openInputStream(parsed)?.use { it.readHtmlLimited(HTML_DOCUMENT_LIMIT) }
                        ?: throw IOException("无法读取附件")
                    String(bytes, Charsets.UTF_8) to ""
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
private fun HtmlPageView(
    source: String,
    baseUrl: String,
    loader: HtmlResourceLoader,
    modifier: Modifier,
    visible: Boolean,
    zoomable: Boolean = true,
    onContentHeight: ((Int) -> Unit)? = null,
) {
    val document = remember(source, baseUrl) { htmlPreviewDocument(source, baseUrl) }
    val currentBase by rememberUpdatedState(baseUrl)
    val currentLoader by rememberUpdatedState(loader)
    val reportHeight by rememberUpdatedState(onContentHeight)
    fun WebView.reportContentHeight() {
        val report = reportHeight ?: return
        evaluateJavascript(
            "(function(){var b=document.body,e=document.documentElement;return Math.max(b?b.scrollHeight:0,b?b.offsetHeight:0,e?e.scrollHeight:0,e?e.offsetHeight:0)})()",
        ) { raw -> raw?.trim('"')?.toIntOrNull()?.takeIf { it > 0 }?.let(report) }
    }
    AndroidView(modifier = modifier, factory = { context -> WebView(context).apply {
        setBackgroundColor(0)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        isNestedScrollingEnabled = false
        setOnTouchListener { view, event ->
            if (!zoomable) {
                view.parent?.requestDisallowInterceptTouchEvent(false)
                if (event.action == android.view.MotionEvent.ACTION_UP) view.performClick()
                return@setOnTouchListener true
            }
            false
        }
        if (zoomable) {
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        }
        // Preview document is https://html-preview...; artifact bytes are injected via interception.
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url?.toString().orEmpty()
                if (request.method != "GET") return blockedHtmlResource()
                val scheme = request.url?.scheme?.lowercase()
                // data/blob/about stay inside the WebView engine; never proxy them through OkHttp.
                if (scheme == "data" || scheme == "blob" || scheme == "about") return null
                val documentUrl = currentBase
                val resourceLoader = currentLoader
                val target = htmlResourceUrl(url, documentUrl) ?: return blockedHtmlResource()
                return try {
                    val resource = resourceLoader.fetch(target, scope = HtmlResourceScope(documentUrl))
                    WebResourceResponse(resource.mimeType, resource.charset, ByteArrayInputStream(resource.bytes))
                } catch (_: Exception) { blockedHtmlResource() }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString().orEmpty()
                if (url.substringBefore('#') == HTML_PREVIEW_ORIGIN.substringBefore('#')) return false
                if (request.url?.fragment != null && url.substringBefore('#') == htmlResourceBase(currentBase).substringBefore('#')) {
                    view.evaluateJavascript("location.hash=" + org.json.JSONObject.quote(request.url.fragment), null)
                } else if (request.hasGesture()) {
                    val mapped = htmlResourceUrl(url, currentBase)
                    val external = mapped ?: resolveHtmlUrl(url, currentBase)?.takeIf { candidate ->
                        val parsed = candidate.toHttpUrlOrNull()
                        parsed != null && parsed.host != Uri.parse(HTML_PREVIEW_ORIGIN).host &&
                            (parsed.scheme == "http" || parsed.scheme == "https")
                    }
                    external?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                view.reportContentHeight()
                view.postDelayed({ view.reportContentHeight() }, 250)
            }
        }
    } }, update = { view ->
        view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
        if (view.tag != document) {
            view.tag = document
            view.loadDataWithBaseURL(HTML_PREVIEW_ORIGIN, document, "text/html", null, null)
        } else {
            view.reportContentHeight()
        }
    }, onRelease = { it.stopLoading(); it.destroy() })
}

private fun blockedHtmlResource() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
