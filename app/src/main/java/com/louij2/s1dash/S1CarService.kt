package com.louij2.s1dash

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * The icon on Android Auto. Android Auto only lets a sideloaded app in as media, messaging
 * or parked, so this is a media app whose "tracks" are readings and Haldex modes. It plays
 * no audio and asks for no audio focus. Never for Play, where it would break the media rules.
 *
 * Trap: Android Auto binds to this whenever the phone connects. The dongle is only taken
 * once a list is really opened, or S1 Dash would take it from SimosTools at every drive.
 */
class S1CarService : MediaBrowserServiceCompat() {
    private lateinit var session: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private var holdingSources = false
    private var shownLive: List<CarRows.Row> = emptyList()
    private var shownModes: List<CarRows.Row> = emptyList()

    private val refresh = object : Runnable {
        override fun run() {
            if (liveRows() != shownLive) notifyChildrenChanged(LIVE)
            if (modeRows() != shownModes) notifyChildrenChanged(MODES)
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DataHub.init(this)
        CalFlash.init(this)
        session = MediaSessionCompat(this, "S1Dash")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                val mode = CarRows.modeFrom(mediaId) ?: return // a reading: nothing to do
                val haldex = DataHub.openHaldex()
                if (haldex == null) showError("OpenHaldex isn't connected") else {
                    haldex.setMode(mode)
                    showIdle()
                }
            }
        })
        showIdle()
        session.isActive = true
        sessionToken = session.sessionToken
    }

    private fun state() = PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)

    private fun showIdle() = session.setPlaybackState(state().setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f).build())

    private fun showError(message: String) = session.setPlaybackState(
        state().setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
            .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, message).build())

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? =
        if (clientPackageName == ANDROID_AUTO || clientPackageName == packageName) BrowserRoot(ROOT, null) else null

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (!holdingSources) {
            holdingSources = true
            DataHub.acquireSources(OWNER)
            handler.postDelayed(refresh, REFRESH_MS)
        }
        result.sendResult(when (parentId) {
            ROOT -> arrayListOf(folder(LIVE, "Live"), folder(MODES, "All-wheel drive"))
            LIVE -> liveRows().also { shownLive = it }.mapTo(ArrayList()) { row(it) }
            MODES -> modeRows().also { shownModes = it }.mapTo(ArrayList()) { row(it) }
            else -> arrayListOf()
        })
    }

    private fun liveRows() = CarRows.live({ DataHub.get(it) }, DataHub.status["ecu"])
    private fun modeRows() = CarRows.modes(DataHub.get("hxMode")?.toInt(), DataHub.status["oh"])

    private fun folder(id: String, title: String) =
        MediaItem(MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(), MediaItem.FLAG_BROWSABLE)

    private fun row(r: CarRows.Row) =
        MediaItem(MediaDescriptionCompat.Builder().setMediaId(r.id).setTitle(r.title).setSubtitle(r.subtitle).build(), MediaItem.FLAG_PLAYABLE)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (holdingSources) DataHub.releaseSources(OWNER)
        session.release()
        super.onDestroy()
    }

    private companion object {
        const val ROOT = "root"
        const val LIVE = "live"
        const val MODES = "modes"
        const val OWNER = "android-auto"
        const val REFRESH_MS = 2000L
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"
    }
}
