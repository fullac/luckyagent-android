package com.luckyagent.android.ui.components

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.luckyagent.android.ui.screens.ChatMediaPreview
import com.luckyagent.android.ui.ChatMedia
import com.luckyagent.android.data.api.MediaAttachment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class HtmlRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun markdownContextsKeepHtmlFormattingLinksAndCodeLiterals() {
        var opened = ""
        compose.setContent { TestHost {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened = uri }
            }) {
                MarkdownText("# Heading <b>bold</b>\n\n- <u>underlined</u>\n\n| Link | Literal |\n| --- | --- |\n| <a href='https://example.test/a(b)'>table link</a> | `<b>x</b>` |")
            }
        } }
        compose.onNodeWithText("Heading bold").assertIsDisplayed()
        compose.onNodeWithText("underlined").assertIsDisplayed()
        compose.onNodeWithText("<b>x</b>").assertIsDisplayed()
        compose.onNodeWithText("table link").performTouchInput { click(androidx.compose.ui.geometry.Offset(24f, center.y)) }
        compose.runOnIdle { assertEquals("https://example.test/a(b)", opened) }
        screenshot("html-native-contexts")
    }

    @Test fun expandedDetailsSurvivesStreamingBodyChangesAndHonorsOpenAttribute() {
        var body by mutableStateOf("first body")
        compose.setContent { TestHost {
            MarkdownText("<details><summary>more</summary>\n\n$body\n</details>")
        } }
        compose.onNodeWithText("first body").assertDoesNotExist()
        compose.onNodeWithText("more").performClick()
        compose.onNodeWithText("first body").assertIsDisplayed()
        compose.runOnIdle { body = "updated body" }
        compose.onNodeWithText("updated body").assertIsDisplayed()
        screenshot("html-details-streaming")
    }

    @Test fun fullHtmlPreviewRendersCssAndDomInteractionAndSwitchesToSource() {
        val source = """<!doctype html><html><head><style>.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.tile{padding:24px;background:#e8f2df}</style></head><body><h1>HTML 页面预览</h1><div class="grid"><div class="tile">CSS Grid</div><div class="tile">完整 HTML</div></div><button id="counter" onclick="this.textContent='已点击'">点击验证</button><script>document.body.dataset.ready='yes'</script></body></html>"""
        compose.setContent { TestHost { MarkdownText("```html\n$source\n```") } }
        compose.onNodeWithText("打开预览 ›").performClick()
        compose.waitUntil(15000) { webView() != null && javascript("document.body.dataset.ready") == "\"yes\"" }
        assertEquals("\"grid\"", javascript("getComputedStyle(document.querySelector('.grid')).display"))
        javascript("document.getElementById('counter').click()")
        assertEquals("\"已点击\"", javascript("document.getElementById('counter').textContent"))
        waitForWebDraw()
        screenshot("html-full-preview")
        compose.onNodeWithText("源码", useUnmergedTree = true).performClick()
        compose.onNodeWithText(source).assertExists()
        screenshot("html-full-source")
        compose.onNodeWithText("预览", useUnmergedTree = true).performClick()
        assertEquals("\"已点击\"", javascript("document.getElementById('counter').textContent"))
        compose.onNodeWithContentDescription("关闭 HTML 预览").performClick()
        compose.onNodeWithText("打开预览 ›").assertIsDisplayed()
    }

    @Test fun htmlAttachmentUsesTheSamePreviewAndLoadsLocalContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "capture/html-test.html").apply {
            parentFile!!.mkdirs()
            writeText("<html><body><h1 id='loaded'>HTML 附件已打开</h1></body></html>")
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val media = ChatMedia(MediaAttachment(type = "document", fileName = "html-test.html", mimeType = "text/html"), localUri = uri.toString())
        compose.setContent { TestHost { ChatMediaPreview(media, emptyMap(), "", onDownload = {}) } }
        compose.onNodeWithText("预览 HTML").performClick()
        compose.waitUntil(15000) { webView() != null && javascript("document.getElementById('loaded')?.textContent") == "\"HTML 附件已打开\"" }
        waitForWebDraw()
        screenshot("html-attachment-preview")
    }

    @Test fun htmlListsTablesImagesAndDefaultOpenDetailsRenderInDarkLargeText() {
        compose.setContent { com.luckyagent.android.ui.theme.CloverTheme(darkTheme = true, fontScale = 1.3f) {
            Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                MarkdownText("<div><h2>HTML 富文本</h2><p><mark>高亮</mark> <del>删除线</del> H<sub>2</sub>O</p><ul><li>第一项</li><li>第二项</li></ul><table><tr><th>名称</th><th>状态</th></tr><tr><td>渲染</td><td>通过</td></tr></table><details open><summary>默认展开</summary><p>可见内容</p></details><img src='data:image/png;base64,invalid' alt='图片加载失败提示'></div>")
            } }
        } }
        compose.onNodeWithText("第一项").assertIsDisplayed()
        compose.onNodeWithText("通过").assertIsDisplayed()
        compose.onNodeWithText("可见内容").assertIsDisplayed()
        compose.onNodeWithText("图片加载失败提示").assertIsDisplayed()
        screenshot("html-native-expanded")
    }

    @Composable private fun TestHost(content: @Composable () -> Unit) {
        MaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) { content() }
        } }
    }

    private fun waitForWebDraw() {
        compose.waitForIdle()
        val view = webView() ?: error("Missing WebView")
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { view.postOnAnimation { latch.countDown() } }
            })
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun webView(): WebView? {
        var found: WebView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun visit(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup) {
                (0 until view.childCount).firstNotNullOfOrNull { visit(view.getChildAt(it)) }
            } else null
            found = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { visit(it) }
        }
        return found
    }

    private fun javascript(script: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        val view = webView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view?.evaluateJavascript(script) { result = it; latch.countDown() } ?: latch.countDown()
        }
        assertTrue("WebView callback timeout", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: instrumentation.targetContext.getExternalFilesDir(null)!!
        directory.mkdirs()
        val target = File(directory, "$name.png")
        target.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
