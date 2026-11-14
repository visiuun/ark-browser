package dev.vis.ark.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.Executors

data class HistoryEntry(val url: String, val title: String, val visits: Int, val lastVisit: Long)

/** Simple SQLite browsing history. Writes go to a single background thread. */
object HistoryStore {
    private lateinit var helper: SQLiteOpenHelper
    private val io = Executors.newSingleThreadExecutor()

    /** Bumped whenever history changes so Compose screens can refresh. */
    val version = mutableIntStateOf(0)

    fun init(ctx: Context) {
        if (::helper.isInitialized) return
        helper = object : SQLiteOpenHelper(ctx.applicationContext, "history.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE history(url TEXT PRIMARY KEY, title TEXT, visits INTEGER, last INTEGER)")
                db.execSQL("CREATE INDEX idx_last ON history(last)")
            }

            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
        }
    }

    private fun bump() { android.os.Handler(android.os.Looper.getMainLooper()).post { version.intValue++ } }

    fun visit(url: String, title: String?) {
        if (!url.startsWith("http")) return
        io.execute {
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()
            db.execSQL(
                "INSERT INTO history(url,title,visits,last) VALUES(?,?,1,?) " +
                    "ON CONFLICT(url) DO UPDATE SET visits=visits+1, last=excluded.last, " +
                    "title=CASE WHEN excluded.title<>'' THEN excluded.title ELSE history.title END",
                arrayOf<Any>(url, title ?: "", now)
            )
            bump()
        }
    }

    fun setTitle(url: String, title: String) {
        if (title.isBlank() || !url.startsWith("http")) return
        io.execute {
            helper.writableDatabase.update("history", ContentValues().apply { put("title", title) }, "url=?", arrayOf(url))
        }
    }

    private fun query(sql: String, args: Array<String>): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += HistoryEntry(c.getString(0), c.getString(1) ?: "", c.getInt(2), c.getLong(3))
        }
        return out
    }

    fun recent(limit: Int = 50): List<HistoryEntry> =
        query("SELECT url,title,visits,last FROM history ORDER BY last DESC LIMIT $limit", emptyArray())

    fun search(q: String, limit: Int = 6): List<HistoryEntry> {
        val like = "%${q.replace("%", "")}%"
        return query(
            "SELECT url,title,visits,last FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visits DESC, last DESC LIMIT $limit",
            arrayOf(like, like)
        )
    }

    fun delete(url: String) = io.execute { helper.writableDatabase.delete("history", "url=?", arrayOf(url)); bump() }
    fun clear() = io.execute { helper.writableDatabase.delete("history", null, null); bump() }
    fun count(): Int = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM history", null).use { it.moveToFirst(); it.getInt(0) }
}
