package com.luckyagent.android.ui.components

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HtmlPreviewTest {
    @Test fun artifactResourcesResolveBesideHtmlInsteadOfBesideApiRoute() {
        val documentUrl = "https://example.test/api/v1/artifacts?path=workspace%2Freports%2Fpage.html"
        val document = Jsoup.parse(htmlPreviewDocument("<img src='images/chart.png'>", documentUrl))
        val resourceUrl = document.selectFirst("img")!!.absUrl("src")
        val resolved = htmlResourceUrl(resourceUrl, documentUrl)!!.toHttpUrl()
        assertEquals("workspace/reports/images/chart.png", resolved.queryParameter("path"))
        assertEquals("example.test", resolved.host)
    }
    @Test fun previewRetainsLayoutAndScriptAndResolvesResources() {
        val source = "<!doctype html><html><head><style>.grid{display:grid}</style></head><body><div class='grid'>x</div><script>window.answer=42</script><img src='photo.png'></body></html>"
        val document = Jsoup.parse(htmlPreviewDocument(source, "https://example.test/reports/page.html"))
        assertEquals("https://example.test/reports/photo.png", document.selectFirst("img")!!.absUrl("src"))
        assertTrue(document.selectFirst("style")!!.data().contains("display:grid"))
        assertTrue(document.selectFirst("script")!!.data().contains("answer=42"))
        assertTrue(document.selectFirst("meta[name=viewport]") != null)
        assertEquals("x", document.selectFirst(".grid")!!.text())
    }
    @Test fun credentialsAreNotForwardedAcrossRedirectOrigins() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/page.html")))
            second.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<p>ok</p>"))
            val loader = HtmlResourceLoader(mapOf("Authorization" to "Bearer sample", "X-API-Key" to "sample"), first.url("/").toString())
            assertEquals("<p>ok</p>", String(loader.fetch(first.url("/page.html").toString()).bytes))
            assertEquals("Bearer sample", first.takeRequest().getHeader("Authorization"))
            val redirected = second.takeRequest()
            assertNull(redirected.getHeader("Authorization"))
            assertNull(redirected.getHeader("X-API-Key"))
        } }
    }
    @Test fun relativeRedirectWithinOriginKeepsCredentials() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/assets/page.html"))
            server.enqueue(MockResponse().setBody("ok"))
            val loader = HtmlResourceLoader(mapOf("X-API-Key" to "sample"), server.url("/").toString())
            val result = loader.fetch(server.url("/page.html").toString())
            assertTrue(result.url.endsWith("/assets/page.html"))
            assertEquals("sample", server.takeRequest().getHeader("X-API-Key"))
            assertEquals("sample", server.takeRequest().getHeader("X-API-Key"))
        }
    }
    @Test fun oversizedDocumentFailsWithDownloadHint() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("long content"))
            try {
                HtmlResourceLoader(emptyMap(), server.url("/").toString()).fetch(server.url("/page").toString(), limit = 4)
                fail("Expected size limit")
            } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("下载")) }
        }
    }
}
