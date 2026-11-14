package dev.vis.ark.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.vis.ark.MainActivity
import dev.vis.ark.R
import dev.vis.ark.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.io.File
import java.net.URLDecoder
import kotlin.concurrent.thread

object Downloads {
    data class Item(val id: Long, val name: String, val uri: String, val mime: String, val size: Long, val time: Long, val source: String)

    val items = mutableStateListOf<Item>()
    /** id -> (name, progress 0..1 or -1 when unknown) */
    val active = mutableStateMapOf<Long, Pair<String, Float>>()
    private val main = Handler(Looper.getMainLooper())
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        val arr = runCatching { JSONArray(Prefs.getString("downloads") ?: "[]") }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items += Item(o.getLong("id"), o.getString("name"), o.getString("uri"), o.optString("mime"), o.optLong("size"), o.optLong("time"), o.optString("source"))
        }
    }

    fun all(): List<Item> { load(); return items }

    private fun persist() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri).put("mime", it.mime).put("size", it.size).put("time", it.time).put("source", it.source)) }
        Prefs.putString("downloads", arr.toString())
    }

    private fun header(r: WebResponse, name: String) = r.headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun fileName(r: WebResponse, mime: String): String {
        val cd = header(r, "content-disposition")
        if (cd != null) {
            Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(cd)?.let { return URLDecoder.decode(it.groupValues[1], "UTF-8").trim('"') }
            Regex("""filename\s*=\s*"?([^";]+)"?""").find(cd)?.let { return it.groupValues[1].trim() }
        }
        var n = if (r.uri.startsWith("http")) URLUtil.guessFileName(r.uri, cd, mime) else "download"
        if (!n.contains('.')) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n += ".$it" }
        return n.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    /** Download a URL through Gecko's network stack (keeps cookies), e.g. from a long-press menu. */
    fun fetch(ctx: Context, url: String, referrer: String?, private: Boolean) {
        val req = WebRequest.Builder(url).apply { if (referrer != null && referrer.startsWith("http")) referrer(referrer) }.build()
        Browser.host?.snack("Downloading…")
        GeckoWebExecutor(Engine.runtime).fetch(req, if (private) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE)
            .accept({ r -> if (r != null) save(ctx, r, private) }, { e -> Browser.host?.snack("Download failed: ${e?.message}") })
    }

    fun save(ctx: Context, r: WebResponse, private: Boolean) {
        load()
        val body = r.body ?: run { Browser.host?.snack("Nothing to download"); return }
        val mime = header(r, "content-type")?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val name = fileName(r, mime)
        val total = header(r, "content-length")?.toLongOrNull() ?: -1L
        saveStream(ctx, body, name, mime, total, r.uri, private)
    }

    fun saveStream(ctx: Context, body: java.io.InputStream, name: String, mime: String, total: Long, source: String, private: Boolean) {
        load()
        val id = System.currentTimeMillis()
        main.post {
            active[id] = name to -1f
            Browser.host?.snack("Downloading $name")
        }
        Notifier.ensureChannels(ctx)
        thread(name = "download-$id") {
            val resolver = ctx.contentResolver
            var outUri: Uri? = null
            var written = 0L
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    outUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception("Storage unavailable")
                } else {
                    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!.apply { mkdirs() }
                    var f = File(dir, name); var i = 1
                    while (f.exists()) f = File(dir, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.', "bin")).also { i++ }
                    outUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                }
                resolver.openOutputStream(outUri!!)!!.use { out ->
                    body.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var last = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); written += n
                            val now = System.currentTimeMillis()
                            if (now - last > 300) {
                                last = now
                                val p = if (total > 0) written.toFloat() / total else -1f
                                main.post { active[id] = name to p }
                                Notifier.progress(ctx, id, name, p)
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) resolver.update(outUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                val item = Item(id, name, outUri.toString(), mime, written, System.currentTimeMillis(), source.take(500))
                main.post {
                    active.remove(id)
                    if (!private) { items.add(0, item); persist() }
                    Browser.host?.snack("Downloaded $name", "Open") { open(ctx, item) }
                }
                Notifier.done(ctx, id, item)
            } catch (e: Exception) {
                runCatching { if (outUri != null && Build.VERSION.SDK_INT >= 29) resolver.delete(outUri, null, null) }
                main.post { active.remove(id); Browser.host?.snack("Download failed: ${e.message ?: name}") }
                Notifier.cancel(ctx, id)
            }
        }
    }

    fun openIntent(item: Item) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(item.uri), item.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(ctx: Context, item: Item) {
        runCatching { ctx.startActivity(Intent.createChooser(openIntent(item), item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Browser.host?.snack("No app can open this file") }
    }

    fun remove(ctx: Context, item: Item, deleteFile: Boolean) {
        items.remove(item); persist()
        if (deleteFile) runCatching { ctx.contentResolver.delete(Uri.parse(item.uri), null, null) }
    }

    fun clear() { items.clear(); persist() }
}

/** Android notifications for downloads and for websites (Web Notifications API). */
object Notifier : WebNotificationDelegate {
    private const val DL = "downloads"
    private const val WEB = "web"
    private val web = HashMap<String, Pair<Int, WebNotification>>()
    private var nextWebId = 50_000
    private lateinit var ctx: Context

    fun ensureChannels(c: Context) {
        ctx = c.applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(DL) == null) nm.createNotificationChannel(NotificationChannel(DL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        if (nm.getNotificationChannel(WEB) == null) nm.createNotificationChannel(NotificationChannel(WEB, "Website notifications", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun allowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun nm() = ctx.getSystemService(NotificationManager::class.java)

    fun progress(c: Context, id: Long, name: String, p: Float) {
        ensureChannels(c); if (!allowed()) return
        val n = NotificationCompat.Builder(ctx, DL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, (p * 100).toInt().coerceAtLeast(0), p < 0).build()
        nm().notify(id.toInt(), n)
    }

    fun done(c: Context, id: Long, item: Downloads.Item) {
        ensureChannels(c); if (!allowed()) return
        val pi = PendingIntent.getActivity(ctx, id.toInt(), Downloads.openIntent(item), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, DL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(item.name).setContentText("Download complete").setContentIntent(pi).setAutoCancel(true).build()
        nm().notify(id.toInt(), n)
    }

    fun cancel(c: Context, id: Long) { ensureChannels(c); nm().cancel(id.toInt()) }

    override fun onShowNotification(notification: WebNotification) {
        if (!::ctx.isInitialized || !allowed()) return
        ensureChannels(ctx)
        val key = notification.tag.ifBlank { "n${nextWebId}" }
        val id = web[key]?.first ?: nextWebId++
        web[key] = id to notification
        val open = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_WEB_NOTIFICATION).putExtra("tag", key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, WEB).setSmallIcon(R.drawable.ic_stat_ark)
            .setContentTitle(notification.title ?: notification.origin)
            .setContentText(notification.text).setSubText(notification.origin?.let { Uri.parse(it).host })
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.text))
            .setSilent(notification.silent).setContentIntent(pi).setAutoCancel(true).build()
        nm().notify(id, n)
    }

    override fun onCloseNotification(notification: WebNotification) {
        val key = notification.tag
        web.remove(key)?.let { nm().cancel(it.first) }
    }

    fun click(tag: String?) {
        val n = web.remove(tag ?: return) ?: return
        runCatching { n.second.click() }
    }
}
