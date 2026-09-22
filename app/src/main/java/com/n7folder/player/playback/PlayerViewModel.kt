package com.n7folder.player.playback

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Garde le [PlaybackController] en vie pendant les rotations d'écran. */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val playback = PlaybackController(application)

    init {
        EqualizerEngine.init(application)
        playback.connect()
    }

    override fun onCleared() {
        playback.release()
    }
}
