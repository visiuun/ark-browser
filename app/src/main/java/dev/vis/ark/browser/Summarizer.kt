package dev.vis.ark.browser

import kotlin.math.ln
import kotlin.math.max

/**
 * Fully on-device extractive summarizer (no AI service, no network).
 * Scores sentences by keyword frequency (TF-IDF-ish) and position, then keeps the best few in order.
 */
object Summarizer {
    data class Summary(
        val bullets: List<String>,
        val keywords: List<String>,
        val words: Int,
        val sentences: Int,
        val readingMinutes: Int,
        val headings: List<String>,
        val links: Int,
    )

    private val stop = """
        a about above after again against all am an and any are as at be because been before being below between both but by
        can could did do does doing down during each few for from further had has have having he her here hers herself him himself his how
        i if in into is it its itself just me more most my myself no nor not now of off on once only or other our ours ourselves out over own
        same she should so some such than that the their theirs them themselves then there these they this those through to too under until up
        very was we were what when where which while who whom why will with would you your yours yourself yourselves also may might must shall
        one two new like get got use used using said says say make made many much even well back still way since per via
        il lo la le gli un una di da del della che è e per con non si sono ma come anche più
        der die das und ist nicht ein eine zu den mit von auf für sich dem des im
        le la les un une des du de et est pas en que qui dans pour sur au
        el los las y es en que de por con para una un se del
    """.trimIndent().split(Regex("\\s+")).toHashSet()

    private fun tokens(s: String) = Regex("[\\p{L}\\p{N}']+").findAll(s.lowercase()).map { it.value.trim('\'') }
        .filter { it.length > 2 && it !in stop && !it.all(Char::isDigit) }.toList()

    fun summarize(raw: String, headings: List<String> = emptyList(), links: Int = 0, maxBullets: Int = 6): Summary {
        val text = raw.replace(Regex("[ \\t\\u00A0]+"), " ").trim()
        val blocks = text.split(Regex("\\n+")).map { it.trim() }.filter { it.isNotBlank() }
        val sentences = blocks.flatMap { b ->
            Regex("(?<=[.!?…])\\s+(?=[\\p{Lu}\"“(\\d])").split(b)
        }.map { it.trim() }.filter { s -> s.length in 40..420 && s.count { it == ' ' } >= 6 }

        val allTokens = tokens(text)
        val words = Regex("\\S+").findAll(text).count()
        val tf = HashMap<String, Int>()
        allTokens.forEach { tf[it] = (tf[it] ?: 0) + 1 }
        val df = HashMap<String, Int>()
        sentences.forEach { s -> tokens(s).toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = max(1, sentences.size)
        fun weight(w: String) = (tf[w] ?: 0) * ln(1.0 + n.toDouble() / (1 + (df[w] ?: 0)))

        val keywords = tf.keys.sortedByDescending { weight(it) }.take(8)
        val kwSet = keywords.toSet()

        val scored = sentences.mapIndexed { i, s ->
            val tk = tokens(s)
            if (tk.isEmpty()) return@mapIndexed Triple(i, s, 0.0)
            var score = tk.sumOf { weight(it) } / (tk.size + 4)
            score *= 1.0 + tk.count { it in kwSet } * 0.15
            val pos = i.toDouble() / n
            if (pos < 0.15) score *= 1.25
            Triple(i, s, score)
        }
        val chosen = ArrayList<Triple<Int, String, Double>>()
        for (c in scored.sortedByDescending { it.third }) {
            if (chosen.size >= maxBullets) break
            val ct = tokens(c.second).toSet()
            // skip near-duplicates
            if (chosen.any { o -> val ot = tokens(o.second).toSet(); ct.intersect(ot).size > 0.6 * minOf(ct.size, ot.size) }) continue
            chosen += c
        }
        return Summary(
            bullets = chosen.sortedBy { it.first }.map { it.second },
            keywords = keywords,
            words = words,
            sentences = sentences.size,
            readingMinutes = max(1, (words / 230.0).toInt()),
            headings = headings,
            links = links,
        )
    }
}
