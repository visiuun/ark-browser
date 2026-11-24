package dev.vis.ark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.vis.ark.browser.Engine
import dev.vis.ark.browser.Search
import dev.vis.ark.browser.Summarizer
import dev.vis.ark.data.Prefs
import dev.vis.ark.data.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Address bar, Secure DNS, summaries and password import/export: the logic that decides what a page or setting does. */
@RunWith(AndroidJUnit4::class)
class LogicTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        Prefs.init(ctx)
        Vault.init(ctx)
    }

    @Test fun typedAddressesBecomeUrls() {
        assertEquals("https://example.com", Search.resolve("example.com"))
        assertEquals("https://example.com/a?b=c", Search.resolve("  example.com/a?b=c "))
        assertEquals("http://localhost:8080", Search.resolve("localhost:8080"))
        assertEquals("http://192.168.1.1", Search.resolve("192.168.1.1"))
        assertEquals("https://kagi.com/x", Search.resolve("https://kagi.com/x"))
        assertEquals("about:config", Search.resolve("about:config"))
        assertTrue(Search.isUrlLike("sub.domain.co.uk"))
        assertFalse(Search.isUrlLike("hello world"))
        assertFalse(Search.isUrlLike("word"))
    }

    @Test fun searchesGoToTheSearchEngineAndBack() {
        val url = Search.resolve("how fast is ark?")
        assertTrue(url, url.startsWith(Search.engine.template.substringBefore("%s")))
        assertEquals("how fast is ark?", Search.queryOf(url))
        assertNull(Search.queryOf("https://example.com/?q=nope"))
    }

    @Test fun secureDnsAddressesAreValidated() {
        assertTrue(Engine.validDoh("https://dns.quad9.net/dns-query"))
        assertTrue(Engine.validDoh("  https://1.1.1.1/dns-query "))
        assertFalse(Engine.validDoh("http://dns.quad9.net/dns-query"))
        assertFalse(Engine.validDoh("dns.quad9.net"))
        assertFalse(Engine.validDoh(""))
        assertFalse(Engine.validDoh("https://"))
    }

    @Test fun onDeviceSummaryPicksSentences() {
        val text = (1..12).joinToString(" ") { "Sentence number $it talks about the Ark browser engine and how quickly pages render on phones." } +
            "\nAn unrelated closing remark about weather."
        val s = Summarizer.summarize(text, listOf("Speed"), links = 3)
        assertTrue(s.bullets.isNotEmpty())
        assertTrue(s.bullets.size <= 6)
        assertTrue(s.words > 100)
        assertTrue(s.keywords.isNotEmpty())
        assertTrue(Summarizer.summarize("").bullets.isEmpty())
    }

    @Test fun passwordCsvRoundTrip() {
        val site = "ark-test-${System.nanoTime()}.invalid"
        try {
            val csv = "url,username,password\r\nhttps://$site/login,alice,\"p,w\"\"1\"\r\n$site,,skipped-no-user-ok\r\nhttps://$site,bob,\r\n"
            assertEquals(2, Vault.importCsv(csv))
            val mine = Vault.logins.filter { it.host == site }
            assertEquals(setOf("alice", ""), mine.map { it.username }.toSet())
            assertEquals("p,w\"1", mine.first { it.username == "alice" }.password)
            assertEquals("https://$site", mine.first { it.username == "alice" }.origin)
            val out = Vault.exportCsv()
            assertTrue(out.contains("\"https://$site\",\"alice\",\"p,w\"\"1\""))
            // Importing the export again updates in place instead of duplicating.
            Vault.importCsv(out)
            assertEquals(2, Vault.logins.count { it.host == site })
        } finally {
            Vault.logins.filter { it.host == site }.map { it.guid }.forEach { Vault.deleteLogin(it) }
        }
    }
}
