package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.settings.ClientSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstConnectionTest {
    @Test fun freshInstallNeedsAnAddressAndKeyBeforeChat() {
        val fresh = ClientSettings(apiBase = "http://10.0.2.2:9090", apiKey = "")
        assertTrue(needsConnectionSetup(fresh, healthOk = null))
        assertFalse(needsConnectionSetup(fresh.copy(apiKey = "secret"), healthOk = null))
        assertTrue(needsConnectionSetup(fresh.copy(apiKey = "secret"), healthOk = false))
    }

    @Test fun standaloneChatDoesNotUseTheComputerGate() {
        val standalone = ClientSettings(connectionMode = "standalone", apiBase = "", apiKey = "")
        assertFalse(needsConnectionSetup(standalone, healthOk = false))
    }

    @Test fun manualEntryInfersBearerAndRejectsABareHost() {
        assertNull(manualConnection("192.168.1.8:9090", "secret"))
        assertNull(manualConnection("http://192.168.1.8:9090", "  "))
        val key = manualConnection("http://192.168.1.8:9090/", " secret ")
        assertEquals("http://192.168.1.8:9090", key?.apiBase)
        assertEquals("secret", key?.apiKey)
        assertEquals(false, key?.useBearer)
        val bearer = manualConnection("https://agent.example", "Bearer token")
        assertEquals(true, bearer?.useBearer)
        assertEquals("token", bearer?.apiKey)
    }

    @Test fun successNamesTheComputerInsteadOfTheWordDefault() {
        assertEquals("书房电脑", connectionHostLabel("http://192.168.1.8:9090", "书房电脑 · 24h"))
        assertEquals("192.168.1.8", connectionHostLabel("http://192.168.1.8:9090", "Default"))
        assertEquals("这台电脑", connectionHostLabel("", "Runtime"))
    }
}
