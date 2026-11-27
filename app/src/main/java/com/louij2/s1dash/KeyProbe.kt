package com.louij2.s1dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Listens for car buttons that never reach S1 Dash as key events, while the key tester is open.
 * Steering-wheel and MMI buttons usually go to the media session that last played sound,
 * or the head unit's own MCU service turns them into broadcast intents. Each catch goes to
 * [report] and to logs/keys.log, so the head unit's own actions can be learned and used.
 */
class KeyProbe(private val ctx: Context, private val report: (String, String) -> Unit) {
    private var session: MediaSessionCompat? = null
    private var receiver: BroadcastReceiver? = null
    private var logcat: Process? = null
    private var track: AudioTrack? = null

    /** What's listening, for the tester's status line. */
    fun start(): String {
        stop()
        val on = ArrayList<String>()
        on += "key events"
        if (startSession()) on += "media buttons"
        if (startReceiver()) on += "head-unit broadcasts"
        on += if (startLogcat()) "system log" else "system log: off (allow it once with adb, see the tester)"
        write("--- key tester opened on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        return on.joinToString(", ")
    }

    fun stop() {
        try { session?.run { isActive = false; release() } } catch (_: Exception) {}
        session = null
        try { receiver?.let { ctx.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null
        logcat?.destroy()
        logcat = null
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    private fun caught(what: String, from: String) {
        write("$what  ·  $from")
        report(what, from)
    }

    // ---- 1. media session: what the wheel's next / previous / play usually reach ----
    private fun startSession(): Boolean = try {
        val s = MediaSessionCompat(ctx, "S1Dash-keys")
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(i: Intent): Boolean {
                @Suppress("DEPRECATION")
                val e = i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (e != null && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0)
                    caught(KeyEvent.keyCodeToString(e.keyCode) + " " + e.keyCode, "media button · " + (e.device?.name ?: "no device"))
                return true // the tester swallows them; music isn't touched while it's open
            }
            override fun onPlay() = caught("play", "media session")
            override fun onPause() = caught("pause", "media session")
            override fun onStop() = caught("stop", "media session")
            override fun onSkipToNext() = caught("next", "media session")
            override fun onSkipToPrevious() = caught("previous", "media session")
            override fun onFastForward() = caught("fast forward", "media session")
            override fun onRewind() = caught("rewind", "media session")
            override fun onCustomAction(action: String, extras: Bundle?) = caught("custom $action", "media session")
            override fun onPlayFromSearch(query: String?, extras: Bundle?) = caught("voice search: ${query.orEmpty()}", "media session")
        })
        s.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND)
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f).build())
        s.isActive = true
        session = s
        playSilence()
        true
    } catch (_: Exception) { false }

    /**
     * Since Android 8 the buttons go to the app that last played sound, not to whichever session
     * says it's playing. A moment of silence makes that S1 Dash, without asking for audio focus,
     * so music and CarPlay keep playing. Closing the tester releases the session and the buttons go back.
     */
    private fun playSilence() {
        val rate = 44100
        val n = rate / 2
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build()
        t.write(ShortArray(n), 0, n)
        t.play()
        track = t
    }

    // ---- 2. broadcasts that head units' MCU services are known to send for wheel keys ----
    private fun startReceiver(): Boolean = try {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val extras = i.extras?.let { b -> b.keySet().joinToString(" ") { k -> "$k=${b.get(k)}" } }.orEmpty()
                caught(i.action + if (extras.isNotEmpty()) "  $extras" else "", "broadcast")
            }
        }
        val f = IntentFilter()
        VENDOR_ACTIONS.forEach { f.addAction(it) }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
        receiver = r
        true
    } catch (_: Exception) { false }

    // ---- 3. the system log: shows every vendor action and where Android sent a key ----
    private fun startLogcat(): Boolean {
        if (ctx.checkSelfPermission(READ_LOGS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val p = ProcessBuilder("logcat", "-v", "brief", "-T", "1").redirectErrorStream(true).start()
            logcat = p
            Thread({
                var last = ""
                var window = 0L
                var count = 0
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (!LOG_MATCH.containsMatchIn(line) || line.contains("s1dash", true) || line.contains("S1Dash-keys")) return@forEachLine
                        val now = System.currentTimeMillis()
                        if (now - window > 1000) { window = now; count = 0 }
                        val text = line.trim().take(160)
                        if (text == last || ++count > 6) return@forEachLine // a busy log mustn't flood the tester
                        last = text
                        caught(text, "system log")
                    }
                } catch (_: Exception) {}
            }, "key-logcat").start()
            true
        } catch (_: Exception) { false }
    }

    private fun write(line: String) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            FileWriter(File(DataHub.Logger.dir(), "keys.log"), true).use { it.write("$stamp  $line\n") }
        } catch (_: Exception) {}
    }

    companion object {
        const val READ_LOGS = "android.permission.READ_LOGS"
        /** Actions head units use for wheel and panel keys. The system log shows any others. */
        val VENDOR_ACTIONS = listOf(
            "com.microntek.irkeyDown", "com.microntek.irkeyUp",                    // MTCx (PX5 and similar)
            "com.android.music.musicservicecommand",                               // many units' wheel next / previous
            "com.android.music.musicservicecommand.next", "com.android.music.musicservicecommand.previous",
            "com.android.music.musicservicecommand.togglepause", "com.android.music.musicservicecommand.pause",
            "com.nwd.action.ACTION_KEY_VALUE",                                     // NWD
            Intent.ACTION_MEDIA_BUTTON,
        )
        private val LOG_MATCH = Regex("(?i)(keycode|key_?event|keyevent|irkey|swc|steer|mfsw|wheel|canbox|media_?button|mcu.{0,8}key|key.{0,8}mcu)")
    }
}
