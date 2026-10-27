package de.hyprnode.slimy.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import de.hyprnode.slimy.model.Settings
import de.hyprnode.slimy.model.Sound
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The kinds of notification haptics, like iOS's `UINotificationFeedbackGenerator.FeedbackType`. */
enum class HapticKind { success, warning, error }

/**
 * Sounds, music and haptics, each switchable in Settings. Like the iPhone's
 * ambient audio session it mixes with other audio (no audio focus is taken)
 * and stays quiet while the phone is on silent or vibrate. Thread-safe.
 *
 * `Feedback.shared` is a silent no-op until the app calls [install].
 */
class Feedback private constructor(
    private val context: Context?,
    private val settings: () -> Settings,
) {
    private val lock = Any()
    private val effects = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val pool: SoundPool? = context?.let {
        SoundPool.Builder().setMaxStreams(12).setAudioAttributes(effects).build()
    }

    /** Loaded sample ids, durations and when each of the (at most 3) voices of a sound started. */
    private val samples = HashMap<Sound, Int>()
    private val ready = HashSet<Int>()
    private val durations = HashMap<Sound, Long>()
    private val voices = HashMap<Sound, ArrayDeque<Long>>()
    private val lastPlayed = HashMap<Sound, Long>()

    private var music: MediaPlayer? = null
    private var musicWanted = false

    private val audio: AudioManager? = context?.let { ContextCompat.getSystemService(it, AudioManager::class.java) }
    private val vibrator: Vibrator? = context?.let { ctx ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getSystemService(ctx, VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.getSystemService(ctx, Vibrator::class.java)
        }
    }?.takeIf { it.hasVibrator() }

    init {
        if (context != null && pool != null) {
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) synchronized(lock) { ready.add(sampleId) }
            }
            for (sound in Sound.entries) load(context, pool, sound)
            // Silent mode switched while the music plays.
            ContextCompat.registerReceiver(
                context,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        updateMusic(active = musicWanted)
                    }
                },
                IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Plays a sound (and its haptic, if it has one). The same sound at most every 60 ms. */
    fun play(sound: Sound) {
        haptic(sound)
        val pool = pool ?: return
        if (!settings().sound || isSilenced) return
        val now = SystemClock.elapsedRealtime()
        val sample = synchronized(lock) {
            // The same sound at most every 60 ms, so fast taps don't turn into noise.
            val last = lastPlayed[sound]
            if (last != null && now - last < 60) return
            lastPlayed[sound] = now
            val id = samples[sound]?.takeIf { it in ready } ?: return
            // At most three voices of one sound at once, like the three players per sound on iOS.
            val length = durations[sound] ?: 500L
            val playing = voices.getOrPut(sound) { ArrayDeque() }
            while (playing.isNotEmpty() && now - playing.first() >= length) playing.removeFirst()
            if (playing.size >= 3) return
            playing.addLast(now)
            id
        }
        pool.play(sample, 0.7f, 0.7f, 1, 0, 1f)
    }

    /** A notification haptic: success, warning or error. */
    fun haptic(kind: HapticKind) {
        if (!settings().haptics) return
        when (kind) {
            HapticKind.success -> vibrate(predefined = VibrationEffect.EFFECT_DOUBLE_CLICK, timings = longArrayOf(0, 18, 70, 28), amplitudes = intArrayOf(0, 140, 0, 220))
            HapticKind.warning -> vibrate(predefined = VibrationEffect.EFFECT_HEAVY_CLICK, timings = longArrayOf(0, 30, 90, 20), amplitudes = intArrayOf(0, 200, 0, 120))
            HapticKind.error -> vibrate(predefined = null, timings = longArrayOf(0, 25, 60, 25, 60, 35), amplitudes = intArrayOf(0, 200, 0, 200, 0, 255))
        }
    }

    /** A light tap, for buttons and pokes. */
    fun tapHaptic() {
        if (!settings().haptics) return
        vibrate(predefined = VibrationEffect.EFFECT_TICK, timings = longArrayOf(0, 8), amplitudes = intArrayOf(0, 90))
    }

    private fun haptic(sound: Sound) {
        if (!settings().haptics) return
        when (sound) {
            Sound.levelUp, Sound.success, Sound.treasure -> haptic(HapticKind.success)
            Sound.nope, Sound.miss -> haptic(HapticKind.warning)
            Sound.pet, Sound.eat, Sound.land ->
                vibrate(predefined = VibrationEffect.EFFECT_CLICK, timings = longArrayOf(0, 14), amplitudes = intArrayOf(0, 150))
            else -> Unit
        }
    }

    /** Starts or stops the music loop to match Settings, the ringer and whether the app is in front. */
    fun updateMusic(active: Boolean = true) {
        val context = context ?: return
        synchronized(lock) {
            musicWanted = active
            if (!settings().music || !active || isSilenced) {
                music?.takeIf { it.isPlaying }?.pause()
                return
            }
            if (music == null) music = makeMusic(context)
            music?.takeIf { !it.isPlaying }?.start()
        }
    }

    /** Silent or vibrate: like the iPhone's ambient session, no sounds then. */
    private val isSilenced: Boolean
        get() = audio?.ringerMode?.let { it != AudioManager.RINGER_MODE_NORMAL } ?: false

    private fun makeMusic(context: Context): MediaPlayer? = runCatching {
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
        )
        context.assets.openFd("music.wav").use { fd -> player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        player.isLooping = true
        player.setVolume(0.35f, 0.35f)
        player.prepare()
        player
    }.getOrNull()

    private fun vibrate(predefined: Int?, timings: LongArray, amplitudes: IntArray) {
        val vibrator = vibrator ?: return
        runCatching {
            val effect = if (predefined != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(predefined)
            } else if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            vibrator.vibrate(effect)
        }
    }

    private fun load(context: Context, pool: SoundPool, sound: Sound) {
        val id = runCatching {
            context.assets.openFd(sound.fileName).use { fd -> pool.load(fd, 1) }
        }.getOrElse {
            // A compressed asset can't be opened as a file descriptor: load a copy instead.
            runCatching {
                val copy = File(context.cacheDir, "sounds/${sound.fileName}")
                if (!copy.isFile) {
                    copy.parentFile?.mkdirs()
                    context.assets.open(sound.fileName).use { input -> copy.outputStream().use { input.copyTo(it) } }
                }
                pool.load(copy.path, 1)
            }.getOrNull()
        } ?: return
        val length = runCatching { context.assets.open(sound.fileName).use { wavDurationMillis(it.readBytes()) } }.getOrNull()
        synchronized(lock) {
            samples[sound] = id
            if (length != null) durations[sound] = length
        }
    }

    companion object {
        @Volatile
        private var instance: Feedback? = null

        private val silent: Feedback by lazy { Feedback(null) { Settings() } }

        /** The app's feedback; a silent stand-in until [install] runs (previews, tests). */
        val shared: Feedback get() = instance ?: silent

        /** Loads the sounds and starts feedback. Call once from the Application. */
        fun install(context: Context, settings: () -> Settings): Feedback {
            val made = Feedback(context.applicationContext, settings)
            instance = made
            return made
        }

        /** Length of a PCM wav in milliseconds, from its header; null when it isn't one. */
        fun wavDurationMillis(bytes: ByteArray): Long? {
            if (bytes.size < 12) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var pos = 12
            var byteRate = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = buf.getInt(pos + 4)
                if (size < 0) return null
                if (id == "fmt " && pos + 16 <= bytes.size) byteRate = buf.getInt(pos + 8 + 8)
                if (id == "data") return if (byteRate > 0) size.toLong() * 1000 / byteRate else null
                pos += 8 + size + (size and 1)
            }
            return null
        }
    }
}

