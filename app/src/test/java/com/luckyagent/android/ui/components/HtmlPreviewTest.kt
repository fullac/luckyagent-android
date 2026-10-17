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
        val csp = document.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        assertTrue(csp.contains("connect-src 'none'"))
        assertFalse(csp.contains("unsafe-eval"))
    }

    @Test fun absoluteThirdPartyUrlsAreNotProxied() {
        val documentUrl = "https://example.test/api/v1/artifacts?path=workspace%2Freports%2Fpage.html"
        assertNull(htmlResourceUrl("https://evil.test/steal", documentUrl))
        assertNull(htmlResourceUrl("http://127.0.0.1:9/secret", documentUrl))
        assertNull(htmlResourceUrl("https://example.test/api/v1/sessions", documentUrl))
    }

    @Test fun onlyArtifactRoutesCountAsCredentialTargets() {
        val api = "https://example.test/"
        assertTrue(isArtifactResourceUrl("https://example.test/api/v1/artifacts?path=workspace%2Fpage.html", api))
        assertFalse(isArtifactResourceUrl("https://example.test/api/v1/sessions", api))
        assertFalse(isArtifactResourceUrl("https://evil.test/api/v1/artifacts?path=workspace%2Fpage.html", api))
    }

    @Test fun credentialsAreNotForwardedAcrossRedirectOrigins() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/page.html").toString()))
            second.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<p>ok</p>"))
            val loader = HtmlResourceLoader(mapOf("Authorization" to "Bearer sample", "X-API-Key" to "sample"), first.url("/").toString())
            // Non-artifact document URL: may fetch, but must not attach credentials on either hop.
            assertEquals("<p>ok</p>", String(loader.fetch(first.url("/page.html").toString()).bytes))
            assertNull(first.takeRequest().getHeader("Authorization"))
            val redirected = second.takeRequest()
            assertNull(redirected.getHeader("Authorization"))
            assertNull(redirected.getHeader("X-API-Key"))
        } }
    }

    @Test fun artifactCredentialsStayOnArtifactsAndDropOffOrigin() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            val artifact = first.url("/api/v1/artifacts?path=workspace%2Fpage.html")
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/leaked").toString()))
            second.enqueue(MockResponse().setBody("secret"))
            val loader = HtmlResourceLoader(mapOf("X-API-Key" to "sample"), first.url("/").toString())
            assertEquals("secret", String(loader.fetch(artifact.toString()).bytes))
            assertEquals("sample", first.takeRequest().getHeader("X-API-Key"))
            assertNull(second.takeRequest().getHeader("X-API-Key"))
        } }
    }

    @Test fun relativeRedirectWithinArtifactOriginKeepsCredentials() {
        MockWebServer().use { server ->
            server.start()
            val start = server.url("/api/v1/artifacts?path=workspace%2Fpage.html")
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/api/v1/artifacts?path=workspace%2Fassets%2Fpage.html"))
            server.enqueue(MockResponse().setBody("ok"))
            val loader = HtmlResourceLoader(mapOf("X-API-Key" to "sample"), server.url("/").toString())
            val result = loader.fetch(start.toString())
            assertTrue(result.url.contains("assets/page.html") || result.url.contains("assets%2Fpage.html"))
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

    @Test fun nonArtifactDocumentBaseKeepsDirectoryForRelativeAssets() {
        val base = htmlResourceBase("https://example.test/reports/page.html")
        assertTrue(base.endsWith("/reports/") || base.endsWith("/reports"))
        // Still must not proxy absolute third-party URLs for that document.
        assertNull(htmlResourceUrl("https://cdn.example/x.css", "https://example.test/reports/page.html"))
    }
}
