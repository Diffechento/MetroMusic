package com.metromusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.metromusic.BuildConfig
import com.metromusic.MainActivity
import com.metromusic.MetroMusicApp
import com.metromusic.R
import kotlinx.coroutines.launch

/**
 * Holds the one and only player for the process and publishes it as a media session.
 *
 * Everything a music app is expected to do outside its own window — the notification with
 * artwork, lock-screen transport, hardware and Bluetooth buttons, Android Auto — comes from
 * the session, so none of it is implemented here.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, MetroRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Pause when the headphones are unplugged, like every other music player.
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Play into the session the app's effects are attached to, rather than letting the player
        // pick one of its own: the equalizer has to exist before anything is prepared so the
        // settings screen can ask it what its bands are.
        val services = (application as MetroMusicApp).services
        player.setAudioSessionId(services.effects.sessionId)

        // Loudness evened out between tracks, if the user asked for it. It has to be here rather than
        // on the controller's side of the session: what it changes is the player's own volume, and the
        // tags it reads arrive as metadata callbacks that only this side gets.
        player.addListener(
            ReplayGain(services.settings, services.scope) { volume -> player.volume = volume }
        )

        // Which decoder was picked, what format it was handed and why it stopped — the only way to
        // tell "playing silence" from "not playing" without ears on the device. Debug builds only.
        if (BuildConfig.DEBUG) player.addAnalyticsListener(EventLogger())

        // The one thing the session can't guess: which icon is ours. Left alone it uses media3's
        // own generic glyph, so the notification in the shade is not recognisably this app.
        createPlaybackChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PlaybackChannelId)
                .setChannelName(R.string.playback_channel)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_playback) }
        )

        // Where the notification and the session lead when they are tapped — and the reason this is
        // here rather than left out as it was.
        //
        // **What actually puts a badge next to the clock.** The shells that show one (One UI, and
        // HyperOS the same way) do not draw the app's notification icon there at all: SystemUI posts a
        // notification *of its own* for the playing session — on a Galaxy S23, `dumpsys notification`
        // shows `pkg=com.android.systemui channel=MediaOngoingActivity` — and that is the pill with an
        // icon and the track's name. It is built from the media session, so an app cannot draw it, only
        // qualify for it, and the earlier reading here (that the platform suppresses a MediaStyle
        // icon, so a second plain notification is needed to carry one) was answering the wrong
        // question: the pill is not an icon we own.
        //
        // What made this app not qualify, found by diffing our notification against another media3
        // player's on the same phone with the same settings: **`contentIntent=null`**. media3 takes the
        // notification's tap target from the session activity, and with none set there is nothing for
        // the pill to open — as there was nothing for the notification in the shade to open either,
        // which is a plain bug on its own. That, and this service used to rebuild media3's notification
        // to strip its group key, leaving ours the only media notification on the device without one.
        // Both are gone; what is left is the shape every working player has.
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .build()
    }

    /**
     * The channel the media notification lives on: media3's own settings, under an id of ours.
     *
     * `IMPORTANCE_LOW` — where this used to raise it to `DEFAULT`, on the theory that Android gives a
     * silent notification no status-bar icon. That theory is dead (see [onCreate]): the badge comes
     * from the system's own ongoing-activity notification, and the media players that get one all sit
     * at `LOW`. Raising it only risked the notification behaving like an alert.
     */
    private fun createPlaybackChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // Channels left behind by older builds: media3's own, and the one the second "status bar icon"
        // notification used to post to. Nothing posts to either any more, and a dead entry in the
        // system's notification settings invites the user to configure something that does nothing.
        runCatching { manager.deleteNotificationChannel(Media3DefaultChannelId) }
        runCatching { manager.deleteNotificationChannel(StatusIconChannelId) }

        manager.createNotificationChannel(
            NotificationChannel(
                PlaybackChannelId,
                getString(R.string.playback_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away is the one teardown this app is actually told about, and the queue's
        // own writes are debounced — so put it on disk now rather than hope the process lives out
        // the delay. Best effort: if the system kills us first, the last debounced write stands.
        val services = (application as MetroMusicApp).services
        services.scope.launch { services.playbackState.flush() }

        // Swiping the app away while paused should not leave a dead notification behind.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        /** Ours, not media3's `default_channel_id` — see [createPlaybackChannel]. */
        const val PlaybackChannelId = "playback"

        /** What media3 calls its own channel, kept only so the old one can be cleaned up. */
        const val Media3DefaultChannelId = "default_channel_id"

        /** The retired second notification's channel, likewise. */
        const val StatusIconChannelId = "status-icon"
    }
}
