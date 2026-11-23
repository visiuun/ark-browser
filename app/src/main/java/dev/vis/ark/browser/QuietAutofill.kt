package dev.vis.ark.browser

import org.mozilla.geckoview.Autofill
import org.mozilla.geckoview.GeckoSession

/**
 * Sits between a page and Android autofill (GeckoView's own delegate). GeckoView tells Android "the user entered
 * this field" on every tap, and on Android 14+ that opens the system "Sign in to …" sheet each time, so a
 * sign-in field could never be typed into. Here Android hears about a field only the first time it's tapped on
 * a page (like Chrome's sign-in sheet); after that, tapping just brings up the keyboard. Opening another page
 * (or reloading) offers autofill again.
 */
class QuietAutofill(val inner: Autofill.Delegate, private val tab: Tab) : Autofill.Delegate {
    companion object {
        /**
         * Puts GeckoView's own delegate back. GeckoView only removes its bridge (switching tabs, turning autofill
         * off) when the session still holds that exact object, so call this before releaseSession / autofillEnabled.
         */
        fun unwrap(s: GeckoSession?) {
            val q = s?.autofillDelegate as? QuietAutofill ?: return
            s.autofillDelegate = q.inner
        }
    }

    private val offered = HashSet<String>()
    private var page = ""

    private fun key(node: Autofill.Node): String {
        val a = node.attributes
        return listOf(node.domain, node.tag, a["id"], a["name"], a["type"], a["autocomplete"], node.hint.toString()).joinToString("|")
    }

    /** True the first time [node] is entered on this page. */
    private fun firstTime(node: Autofill.Node): Boolean {
        val url = tab.url.substringBefore('#')
        if (url != page) { page = url; offered.clear() }
        return offered.add(key(node))
    }

    override fun onNodeFocus(session: GeckoSession, node: Autofill.Node, data: Autofill.NodeData) {
        if (firstTime(node)) inner.onNodeFocus(session, node, data)
    }

    override fun onSessionStart(session: GeckoSession) = inner.onSessionStart(session)
    override fun onSessionCommit(session: GeckoSession, node: Autofill.Node, data: Autofill.NodeData) = inner.onSessionCommit(session, node, data)
    override fun onSessionCancel(session: GeckoSession) = inner.onSessionCancel(session)
    override fun onNodeAdd(session: GeckoSession, node: Autofill.Node, data: Autofill.NodeData) = inner.onNodeAdd(session, node, data)
    override fun onNodeRemove(session: GeckoSession, node: Autofill.Node, data: Autofill.NodeData) = inner.onNodeRemove(session, node, data)
    override fun onNodeUpdate(session: GeckoSession, node: Autofill.Node, data: Autofill.NodeData) = inner.onNodeUpdate(session, node, data)
    override fun onNodeBlur(session: GeckoSession, node: Autofill.Node, data: Autofill.NodeData) = inner.onNodeBlur(session, node, data)
}
