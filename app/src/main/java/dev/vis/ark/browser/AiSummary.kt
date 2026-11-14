package dev.vis.ark.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI page summaries through Pollinations (https://pollinations.ai): free, open source, no account or key.
 * Only used when the user turns it on, and never for private tabs.
 */
object AiSummary {
    data class Result(val tldr: String, val points: List<String>, val topics: List<String>, val model: String)

    private const val ENDPOINT = "https://text.pollinations.ai/openai"
    private const val MAX_CHARS = 14_000

    private val SYSTEM = """
        You summarize web pages for a mobile browser. Read the page and explain what it is actually about and why it matters.
        Rules:
        - Focus on the main point of the page, not on navigation, ads, cookie notices, comments or related links.
        - Be specific: keep the key names, numbers, dates and conclusions.
        - Write in the same language as the page.
        - Reply with JSON only, no code fences, in exactly this shape:
          {"tldr": "one or two sentences with the core point", "points": ["3 to 6 short key points"], "topics": ["2 to 5 short topics"]}
    """.trimIndent()

    suspend fun summarize(title: String, url: String, text: String): Result = withContext(Dispatchers.IO) {
        val page = text.replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_CHARS)
        val body = JSONObject()
            .put("model", "openai")
            .put("referrer", "ark-browser")
            .put("private", true)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", "Title: $title\nURL: $url\n\n$page"))
            )
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Ark/${dev.vis.ark.BuildConfig.VERSION_NAME}")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode == 429) error("The free AI service is busy — try again in a few seconds")
            if (conn.responseCode != 200) error("AI service error (HTTP ${conn.responseCode})")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            parse(msg.optString("content"), json.optString("model", "AI"))
        } finally {
            conn.disconnect()
        }
    }

    /** Reads the model's JSON; falls back to treating "-" lines as points if it didn't follow the format. */
    internal fun parse(content: String, model: String): Result {
        val raw = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) runCatching {
            val o = JSONObject(raw.substring(start, end + 1))
            fun list(k: String) = o.optJSONArray(k)?.let { a ->
                (0 until a.length()).map { a.optString(it).trim().replace(Regex("^(\\d+[.)]|[-•*])\\s*"), "") }.filter { it.isNotBlank() }
            } ?: emptyList()
            val r = Result(o.optString("tldr").trim(), list("points"), list("topics").take(5), model)
            if (r.tldr.isNotBlank() || r.points.isNotEmpty()) return r
        }
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val points = lines.filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") }.map { it.trimStart('-', '•', '*', ' ') }
        val tldr = lines.firstOrNull { !(it.startsWith("-") || it.startsWith("•") || it.startsWith("*")) } ?: ""
        if (tldr.isBlank() && points.isEmpty()) error("The AI didn't return a summary")
        return Result(tldr, points, emptyList(), model)
    }
}
