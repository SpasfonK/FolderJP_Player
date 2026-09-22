package com.n7folder.player.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.n7folder.player.MainActivity
import com.n7folder.player.playback.EqualizerEngine

/**
 * Lecture en arrière-plan : ExoPlayer + MediaLibrarySession.
 * Media3 fournit la notification de lecture, les commandes de l'écran verrouillé, Bluetooth (AVRCP),
 * les boutons du casque et le passage du service au premier plan pendant la lecture.
 */
class MusicService : MediaLibraryService() {

    private var librarySession: MediaLibrarySession? = null
    private var consecutiveErrors = 0

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // gère le focus audio : pause pendant un appel, volume baissé, etc.
            )
            .setHandleAudioBecomingNoisy(true) // pause si le casque est débranché
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                EqualizerEngine.attach(applicationContext, audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) consecutiveErrors = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                // Fichier corrompu ou format non pris en charge : on passe au suivant plutôt que de
                // bloquer toute la file (mais on s'arrête si une série de pistes échoue d'affilée).
                consecutiveErrors++
                if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        EqualizerEngine.attach(applicationContext, player.audioSessionId)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        librarySession = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {})
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession

    override fun onDestroy() {
        EqualizerEngine.detach()
        val session = librarySession
        if (session != null) {
            session.player.release()
            session.release()
        }
        librarySession = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_CONSECUTIVE_ERRORS = 5
    }
}
