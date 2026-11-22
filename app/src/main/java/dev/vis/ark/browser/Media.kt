package dev.vis.ark.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession as GeckoMedia

/**
 * Web media (the Media Session API, plus any playing <audio>/<video>) on Android's media controls:
 * the notification, the lock screen, Bluetooth headset buttons, Android Auto and smart watches.
 * While something plays, a foreground service keeps it going with the screen off or Ark in the background.
 */
object Media {
    private const val TAG = "ArkMedia"
    const val CHANNEL = "media"
    const val NOTIFICATION_ID = 7_007

    private lateinit var ctx: Context
    private var session: android.media.session.MediaSession? = null

    private var tab: Tab? = null
    private var gecko: GeckoMedia? = null
    private var features = 0L
    private var title = ""
    private var artist = ""
    private var album = ""
    private var art: Bitmap? = null
    private var playing = false
    private var position = PlaybackState.PLAYBACK_POSITION_UNKNOWN
    private var duration = -1L
    private var rate = 1f

    fun init(c: Context) {
        if (::ctx.isInitialized) return
        ctx = c.applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing media", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
    }

    private fun androidSession(): android.media.session.MediaSession {
        session?.let { return it }
        val s = android.media.session.MediaSession(ctx, "Ark")
        s.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() { gecko?.play() }
            override fun onPause() { gecko?.pause() }
            override fun onStop() { gecko?.stop() }
            override fun onSkipToNext() { gecko?.nextTrack() }
            override fun onSkipToPrevious() { gecko?.previousTrack() }
            override fun onFastForward() { gecko?.seekForward() }
            override fun onRewind() { gecko?.seekBackward() }
            override fun onSeekTo(pos: Long) { gecko?.seekTo(pos / 1000.0, true) }
        })
        val open = Intent(ctx, dev.vis.ark.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        s.setSessionActivity(PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        session = s
        return s
    }

    fun delegate(t: Tab) = object : GeckoMedia.Delegate {
        override fun onActivated(s: GeckoSession, m: GeckoMedia) {
            tab = t; gecko = m
            title = t.title; artist = hostOf(t.url); album = ""; art = null
        }

        override fun onDeactivated(s: GeckoSession, m: GeckoMedia) { if (gecko === m) release() }

        override fun onMetadata(s: GeckoSession, m: GeckoMedia, meta: GeckoMedia.Metadata) {
            if (gecko !== m) { tab = t; gecko = m }
            title = meta.title?.takeIf { it.isNotBlank() } ?: t.title
            artist = meta.artist?.takeIf { it.isNotBlank() } ?: hostOf(t.url)
            album = meta.album.orEmpty()
            art = null
            update()
            meta.artwork?.getBitmap(512)?.accept({ bmp -> if (gecko === m) { art = bmp; update() } }, { })
        }

        override fun onFeatures(s: GeckoSession, m: GeckoMedia, f: Long) { features = f; update() }

        override fun onPlay(s: GeckoSession, m: GeckoMedia) {
            tab = t; gecko = m; playing = true
            if (title.isBlank()) title = t.title
            if (artist.isBlank()) artist = hostOf(t.url)
            update()
        }

        override fun onPause(s: GeckoSession, m: GeckoMedia) { if (gecko === m) { playing = false; update() } }
        override fun onStop(s: GeckoSession, m: GeckoMedia) { if (gecko === m) release() }

        override fun onPositionState(s: GeckoSession, m: GeckoMedia, st: GeckoMedia.PositionState) {
            if (gecko !== m) return
            position = (st.position * 1000).toLong()
            duration = if (st.duration.isFinite() && st.duration > 0) (st.duration * 1000).toLong() else -1L
            rate = st.playbackRate.toFloat()
            update()
        }
    }

    /** A tab is closing: drop its controls. */
    fun tabClosed(t: Tab) { if (tab === t) release() }

    private fun hostOf(u: String) = runCatching { android.net.Uri.parse(u).host?.removePrefix("www.") }.getOrNull().orEmpty()

    private fun has(f: Long) = features and f != 0L

    private fun update() {
        if (!::ctx.isInitialized || gecko == null) return
        val s = androidSession()
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (has(GeckoMedia.Feature.NEXT_TRACK)) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (has(GeckoMedia.Feature.PREVIOUS_TRACK)) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (has(GeckoMedia.Feature.SEEK_TO) || duration > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        if (has(GeckoMedia.Feature.SEEK_FORWARD)) actions = actions or PlaybackState.ACTION_FAST_FORWARD
        if (has(GeckoMedia.Feature.SEEK_BACKWARD)) actions = actions or PlaybackState.ACTION_REWIND
        s.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, if (playing) rate else 0f)
                .build(),
        )
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        if (duration > 0) md.putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        art?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        s.setMetadata(md.build())
        s.isActive = true

        if (playing) {
            runCatching { ctx.startForegroundService(Intent(ctx, MediaService::class.java)) }
                .onFailure { Log.w(TAG, "couldn't start media service", it); notifyOnly() }
        } else notifyOnly()
    }

    private fun notifyOnly() {
        if (gecko == null) return
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    private fun action(icon: Int, label: String, code: String): Notification.Action {
        val i = Intent(ctx, MediaService::class.java).setAction(code)
        val pi = PendingIntent.getService(ctx, code.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, icon), label, pi).build()
    }

    fun notification(): Notification {
        val s = androidSession()
        val acts = mutableListOf<Notification.Action>()
        if (has(GeckoMedia.Feature.PREVIOUS_TRACK)) acts += action(android.R.drawable.ic_media_previous, "Previous", "prev")
        acts += if (playing) action(android.R.drawable.ic_media_pause, "Pause", "pause") else action(android.R.drawable.ic_media_play, "Play", "play")
        if (has(GeckoMedia.Feature.NEXT_TRACK)) acts += action(android.R.drawable.ic_media_next, "Next", "next")
        val compact = IntArray(acts.size) { it }.take(3).toIntArray()
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(dev.vis.ark.R.drawable.ic_stat_ark)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(art)
            .setOngoing(playing)
            .setVisibility(if (tab?.private == true) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setContentIntent(s.controller.sessionActivity)
            .setDeleteIntent(PendingIntent.getService(ctx, 9, Intent(ctx, MediaService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(*compact))
        acts.forEach { b.addAction(it) }
        return b.build()
    }

    fun handle(action: String?) {
        when (action) {
            "play" -> gecko?.play()
            "pause" -> gecko?.pause()
            "next" -> gecko?.nextTrack()
            "prev" -> gecko?.previousTrack()
            "stop" -> { gecko?.pause(); release() }
        }
    }

    val isPlaying get() = playing && gecko != null

    private fun release() {
        playing = false; gecko = null; tab = null; features = 0; art = null; position = PlaybackState.PLAYBACK_POSITION_UNKNOWN; duration = -1
        session?.run { isActive = false; release() }
        session = null
        if (::ctx.isInitialized) {
            runCatching { ctx.stopService(Intent(ctx, MediaService::class.java)) }
            runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        }
    }
}

/** Keeps web audio/video playing while Ark is in the background, with the media notification on top. */
class MediaService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Media.init(this)
        val n = Media.notification()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Media.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(Media.NOTIFICATION_ID, n)
        }
        intent?.action?.let { Media.handle(it) }
        if (!Media.isPlaying) {
            stopForeground(STOP_FOREGROUND_DETACH)
            if (intent?.action == "stop") stopSelf()
        }
        return START_NOT_STICKY
    }
}
