package com.turbotext.app

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Generates a handful of simple synthesized WAV tones directly — no
 *  bundled audio assets, no network fetch — so there's always something
 *  usable in the notification sounds folder the first time the app runs,
 *  before the user has added any of their own files. Purely a starting
 *  point: these are ordinary files in that folder like any other, and
 *  never overwritten once they exist, so replacing or deleting them
 *  works exactly like it would for a user-added sound. */
object DefaultNotificationSounds {
    private const val SAMPLE_RATE = 22050

    /** A named sound is one or more (start freq Hz, end freq Hz,
     *  duration ms) tone segments played back to back — a flat tone
     *  when start == end, a sweep otherwise, or silence when both are
     *  zero (used for the gap in "Double Beep"). */
    private data class Tone(val freqStart: Double, val freqEnd: Double, val durationMs: Int)

    private val sounds: Map<String, List<Tone>> = linkedMapOf(
        "Chime" to listOf(Tone(880.0, 880.0, 300)),
        "Beep" to listOf(Tone(1200.0, 1200.0, 150)),
        "Double Beep" to listOf(Tone(1000.0, 1000.0, 120), Tone(0.0, 0.0, 80), Tone(1000.0, 1000.0, 120)),
        "Rising Tone" to listOf(Tone(500.0, 1400.0, 350)),
        "Descending Tone" to listOf(Tone(1400.0, 500.0, 350)),
        "Ping" to listOf(Tone(1800.0, 1800.0, 200))
    )

    /** A silent WAV of the given duration — used as a "wakeup" preamble
     *  played immediately before the real notification sound (see
     *  SoundNotificationHelper.playSoundWithWakeup), so a sleeping
     *  Bluetooth link has something to rouse it before anything audible
     *  starts. Reuses the same writeWav() a real (audible) sound goes
     *  through, just with a single all-silent Tone segment — not written
     *  into the user-visible sounds folder, so it never shows up as a
     *  selectable notification sound itself. */
    fun writeSilenceWav(file: File, durationMs: Int) {
        try {
            writeWav(file, listOf(Tone(0.0, 0.0, durationMs)))
        } catch (e: Exception) {
            android.util.Log.w("DefaultNotificationSounds", "couldn't write wakeup silence", e)
        }
    }

    /** Writes each sound above into [dir] as a .wav file — but only if
     *  that name doesn't already exist there, so this never overwrites
     *  a file the user replaced or customized, and only fills in
     *  whichever of the six (if any) are actually missing. */
    fun ensureDefaultsExist(dir: File) {
        try {
            dir.mkdirs()
            for ((name, tones) in sounds) {
                val file = File(dir, "$name.wav")
                if (file.exists()) continue
                writeWav(file, tones)
            }
        } catch (e: Exception) {
            android.util.Log.w("DefaultNotificationSounds", "couldn't seed default sounds", e)
        }
    }

    private fun writeWav(file: File, tones: List<Tone>) {
        val samples = mutableListOf<Short>()
        for (tone in tones) {
            val sampleCount = (SAMPLE_RATE * tone.durationMs / 1000.0).toInt()
            if (tone.freqStart == 0.0 && tone.freqEnd == 0.0) {
                repeat(sampleCount) { samples.add(0) }
                continue
            }
            // Short fade in/out avoids an audible click at each segment's
            // start/end — capped at 1/6 of the segment or 20ms, whichever
            // is shorter, so even the short "Double Beep" segments still
            // get a real, audible flat portion in the middle.
            val fadeSamples = min(sampleCount / 6, SAMPLE_RATE / 50)
            for (i in 0 until sampleCount) {
                val t = i.toDouble() / sampleCount
                val freq = tone.freqStart + (tone.freqEnd - tone.freqStart) * t
                val phase = 2.0 * PI * freq * (i.toDouble() / SAMPLE_RATE)
                var amplitude = 0.6 // headroom below full scale, avoids clipping
                if (i < fadeSamples) amplitude *= i.toDouble() / fadeSamples
                if (i > sampleCount - fadeSamples) amplitude *= (sampleCount - i).toDouble() / fadeSamples
                samples.add((sin(phase) * amplitude * Short.MAX_VALUE).toInt().toShort())
            }
        }

        val dataSize = samples.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes("RIFF")
            raf.write(intToLeBytes(36 + dataSize))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.write(intToLeBytes(16))
            raf.write(shortToLeBytes(1)) // PCM
            raf.write(shortToLeBytes(1)) // mono
            raf.write(intToLeBytes(SAMPLE_RATE))
            raf.write(intToLeBytes(SAMPLE_RATE * 2)) // byte rate = sampleRate * blockAlign
            raf.write(shortToLeBytes(2)) // block align = channels * bitsPerSample/8
            raf.write(shortToLeBytes(16)) // bits per sample
            raf.writeBytes("data")
            raf.write(intToLeBytes(dataSize))
            val buffer = java.nio.ByteBuffer.allocate(dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buffer.putShort(s)
            raf.write(buffer.array())
        }
    }

    private fun intToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    private fun shortToLeBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )
}
