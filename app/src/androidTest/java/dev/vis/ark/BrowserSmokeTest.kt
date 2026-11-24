package dev.vis.ark

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.vis.ark.browser.Browser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real app end to end: another app hands Ark a link, the page loads in a new tab, and the tab list on disk
 * (written off the main thread) holds it, ready to be restored on the next start.
 */
@RunWith(AndroidJUnit4::class)
class BrowserSmokeTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(block: () -> T): T {
        var r: Result<T>? = null
        inst.runOnMainSync { r = runCatching(block) }
        return r!!.getOrThrow()
    }

    private fun waitFor(what: String, timeoutMs: Long = 45_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (onMain(cond)) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun page(title: String) =
        "data:text/html;charset=utf-8," + Uri.encode("<!doctype html><title>$title</title><h1>$title</h1><a href='#x'>link</a>")

    @Test fun linkFromAnotherAppLoadsAndIsSaved() {
        val title = "ArkSmoke${System.nanoTime()}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(page(title)))
            .setClassName(inst.targetContext, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor("the page to load") { Browser.tabs.any { t -> t.title == title && !t.loading && t.progress == 100 } }
            val tab = onMain { Browser.tabs.first { t -> t.title == title } }
            assertEquals(tab.id, onMain { Browser.currentId })

            // A second tab, then back: switching keeps both alive and the right one current.
            val second = "ArkSmoke2-${System.nanoTime()}"
            onMain { Browser.newTab(page(second)) }
            waitFor("the second tab") { Browser.tabs.any { t -> t.title == second && !t.loading } }
            onMain { Browser.select(tab) }
            assertEquals(tab.id, onMain { Browser.currentId })

            onMain { Browser.saveNow(sync = true) }
            val saved = File(inst.targetContext.filesDir, "tabs.json")
            assertTrue("tabs.json written", saved.exists())
            val o = JSONObject(saved.readText())
            val arr = o.getJSONArray("tabs")
            val titles = (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
            assertTrue(titles.toString(), title in titles && second in titles)
            assertEquals(tab.id, o.getLong("current"))

            // Closing both leaves no trace of them in the saved list.
            onMain { Browser.tabs.filter { t -> t.title == title || t.title == second }.forEach { t -> Browser.close(t, undoable = false) } }
            onMain { Browser.saveNow(sync = true) }
            assertTrue(!saved.readText().contains(title))
        }
    }

    @Test fun backgroundTabsUnloadAndComeBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val made = onMain { (1..9).map { i -> Browser.newTab(page("Unload$i"), select = true) } }
            waitFor("pages and their saved state") { made.all { t -> !t.loading && t.progress == 100 && t.liveState != null } }
            onMain { Browser.select(made.last()); Browser.trimSessions() }
            val live = onMain { made.count { t -> t.session != null } }
            assertTrue("live sessions: $live", live <= 6) // the current tab plus five in the background
            val unloaded = onMain { made.first { t -> t.session == null } }
            onMain { Browser.select(unloaded) }
            waitFor("the unloaded tab to come back") { unloaded.session != null && !unloaded.loading && unloaded.title == "Unload1" }
            onMain { made.forEach { t -> Browser.close(t, undoable = false) } }
        }
    }

    @Test fun manyTabsOpenAndCloseWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val made = onMain { (1..12).map { i -> Browser.newTab(page("Stress$i"), select = i % 3 == 0) } }
            waitFor("all pages") { made.all { t -> !t.loading && t.progress == 100 } }
            onMain { made.shuffled().forEach { t -> Browser.close(t, undoable = false) } }
            assertTrue(onMain { made.none { t -> t in Browser.tabs } })
        }
    }
}
