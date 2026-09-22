package com.n7folder.player.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** État de l'égaliseur : gains en dB, un par bande. */
data class EqState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val labels: List<String> = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"),
    val gains: List<Float> = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    val minDb: Float = -12f,
    val maxDb: Float = 12f
)

/**
 * Égaliseur du lecteur. Le service (qui possède ExoPlayer) et l'interface vivent dans le même
 * processus : cet objet est le point de rencontre, sans IPC.
 *
 * - Android 9+ : DynamicsProcessing, 10 bandes (31 Hz à 16 kHz).
 * - Android 8.x : Equalizer classique, avec le nombre de bandes de l'appareil (souvent 5).
 * Si l'appareil refuse l'effet, [EqState.supported] reste faux et l'écran l'indique.
 */
object EqualizerEngine {
    val DEFAULT_LABELS: List<String> =
        listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    /** Fréquence de coupure haute de chaque bande (Hz). */
    private val CUTOFFS = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)
    private const val BAND_COUNT = 10
    private const val PREFS_NAME = "n7_equalizer"

    val PRESETS: Map<String, List<Float>> = linkedMapOf(
        "Plat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Basses +" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Voix" to listOf(-2f, -1f, 0f, 1f, 3f, 4f, 3f, 1f, 0f, -1f),
        "Rock" to listOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 3f, 4f, 4f),
        "Classique" to listOf(0f, 0f, 0f, 0f, 0f, 0f, -2f, -2f, -2f, -4f),
        "Aigus +" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)
    )

    private val lock = Any()
    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var dynamics: DynamicsProcessing? = null
    private var classic: Equalizer? = null
    private var classicMinMillibel = -1500
    private var classicMaxMillibel = 1500

    /** Charge les réglages sauvegardés ; à appeler tôt, depuis l'interface comme depuis le service. */
    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            val saved = parseGains(p.getString("gains", null))
            _state.update { it.copy(enabled = p.getBoolean("enabled", false), gains = saved ?: it.gains) }
        }
    }

    /** Branche l'égaliseur sur la session audio d'ExoPlayer. */
    fun attach(context: Context, audioSessionId: Int) {
        init(context)
        synchronized(lock) {
            releaseEffects()
            if (audioSessionId <= 0) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    attachDynamics(audioSessionId)
                } else {
                    attachClassic(audioSessionId)
                }
            } catch (e: Exception) {
                // Effet refusé par l'appareil : on continue sans égaliseur.
                releaseEffects()
                _state.update { it.copy(supported = false) }
                return
            }
            applyLocked()
        }
    }

    fun detach() {
        synchronized(lock) { releaseEffects() }
    }

    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            _state.update { it.copy(enabled = on) }
            persistLocked()
            applyLocked()
        }
    }

    fun setGain(band: Int, db: Float) {
        synchronized(lock) {
            val current = _state.value
            if (band in current.gains.indices) {
                val updated = current.gains.toMutableList()
                updated[band] = db.coerceIn(current.minDb, current.maxDb)
                _state.update { it.copy(gains = updated) }
                persistLocked()
                applyLocked()
            }
        }
    }

    fun applyPreset(name: String) {
        synchronized(lock) {
            val preset = PRESETS[name]
            if (preset != null) {
                val count = _state.value.gains.size
                val resampled = List(count) { i -> preset[(i * preset.size) / count] }
                _state.update { it.copy(gains = resampled) }
                persistLocked()
                applyLocked()
            }
        }
    }

    fun reset() {
        applyPreset("Plat")
    }

    // ------------------------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.P)
    private fun attachDynamics(session: Int) {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true,
            BAND_COUNT,
            false,
            0,
            false,
            0,
            false
        ).build()
        dynamics = DynamicsProcessing(0, session, config)
        _state.update {
            it.copy(
                supported = true,
                labels = DEFAULT_LABELS,
                minDb = -12f,
                maxDb = 12f,
                gains = fit(it.gains, BAND_COUNT)
            )
        }
    }

    private fun attachClassic(session: Int) {
        val eq = Equalizer(0, session)
        classic = eq
        val bands = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        classicMinMillibel = range[0].toInt()
        classicMaxMillibel = range[1].toInt()
        val labels = ArrayList<String>(bands)
        for (i in 0 until bands) labels.add(formatHz(eq.getCenterFreq(i.toShort())))
        _state.update {
            it.copy(
                supported = true,
                labels = labels,
                minDb = classicMinMillibel / 100f,
                maxDb = classicMaxMillibel / 100f,
                gains = fit(it.gains, bands)
            )
        }
    }

    private fun applyLocked() {
        val s = _state.value
        val dp = dynamics
        if (dp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) applyDynamics(dp, s)
        val eq = classic
        if (eq != null) applyClassic(eq, s)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamics(dp: DynamicsProcessing, s: EqState) {
        try {
            var maxGain = 0f
            for (g in s.gains) if (g > maxGain) maxGain = g
            for (i in 0 until BAND_COUNT) {
                val gain = if (i < s.gains.size) s.gains[i] else 0f
                dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, CUTOFFS[i], gain))
            }
            // Gain d'entrée négatif = marge pour que les bandes relevées n'écrêtent pas.
            dp.setInputGainAllChannelsTo(-maxGain)
            dp.setEnabled(s.enabled)
        } catch (e: Exception) {
            // Paramètre refusé par le pilote audio : on garde l'effet tel quel.
        }
    }

    private fun applyClassic(eq: Equalizer, s: EqState) {
        try {
            for (i in s.gains.indices) {
                val millibel = (s.gains[i] * 100f).toInt().coerceIn(classicMinMillibel, classicMaxMillibel)
                eq.setBandLevel(i.toShort(), millibel.toShort())
            }
            eq.setEnabled(s.enabled)
        } catch (e: Exception) {
            // Idem : jamais de plantage à cause d'un effet audio.
        }
    }

    private fun releaseEffects() {
        try {
            dynamics?.release()
        } catch (e: Exception) {
            // Déjà libéré.
        }
        try {
            classic?.release()
        } catch (e: Exception) {
            // Déjà libéré.
        }
        dynamics = null
        classic = null
    }

    private fun persistLocked() {
        val s = _state.value
        prefs?.edit()
            ?.putBoolean("enabled", s.enabled)
            ?.putString("gains", s.gains.joinToString(","))
            ?.apply()
    }

    private fun parseGains(raw: String?): List<Float>? {
        if (raw.isNullOrBlank()) return null
        val out = ArrayList<Float>()
        for (part in raw.split(',')) {
            val value = part.trim().toFloatOrNull() ?: return null
            out.add(value)
        }
        return out
    }

    /** Adapte la liste de gains au nombre de bandes de l'appareil (remise à zéro si différent). */
    private fun fit(gains: List<Float>, bands: Int): List<Float> =
        if (gains.size == bands) gains else List(bands) { 0f }

    private fun formatHz(milliHertz: Int): String {
        val hz = milliHertz / 1000
        if (hz < 1000) return hz.toString()
        val tenths = hz / 100
        return if (tenths % 10 == 0) {
            (tenths / 10).toString() + "k"
        } else {
            (tenths / 10).toString() + "." + (tenths % 10).toString() + "k"
        }
    }
}
