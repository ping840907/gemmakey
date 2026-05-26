package com.moneytalks.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG           = "AudioRecorder"
private const val SAMPLE_RATE   = 16_000
private const val CHANNEL_CFG   = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FMT     = AudioFormat.ENCODING_PCM_16BIT
private const val MAX_SECONDS   = 30

/**
 * Captures PCM audio from the microphone and wraps it in a WAV container.
 *
 * [start] runs the read loop on the IO dispatcher; each 100 ms chunk is
 * appended to [pcmBuffer] under [lock].
 * [stopAndGetWav] flips [isRecording] and snapshots the buffer — never blocks
 * the main thread, safe to call from a click handler.
 * Returns null when fewer than ~0.1 s of audio was captured.
 */
class AudioRecorder {
    @Volatile private var isRecording = false
    private val pcmBuffer = ByteArrayOutputStream()
    private val lock = Any()

    suspend fun start(onAmplitude: ((Float) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val minBuf    = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT)
        val chunkBytes = (SAMPLE_RATE * 2 * 0.1).toInt().coerceAtLeast(minBuf)

        @Suppress("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CFG, AUDIO_FMT,
            chunkBytes * 4
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.e(TAG, "AudioRecord failed to initialise")
            return@withContext
        }

        synchronized(lock) { pcmBuffer.reset() }
        isRecording = true
        record.startRecording()
        Log.i(TAG, "Recording started — sampleRate=$SAMPLE_RATE chunkBytes=$chunkBytes")

        val buf      = ByteArray(chunkBytes)
        val maxBytes = SAMPLE_RATE * 2 * MAX_SECONDS
        var total    = 0

        while (isActive && isRecording && total < maxBytes) {
            val n = record.read(buf, 0, buf.size)
            if (n <= 0) break
            total += n
            synchronized(lock) { pcmBuffer.write(buf, 0, n) }
            onAmplitude?.invoke(peakAmplitude(buf, n))
        }

        record.stop()
        record.release()
        Log.i(TAG, "Recording ended — totalBytes=$total")
    }

    fun stopAndGetWav(): ByteArray? {
        isRecording = false
        val pcm = synchronized(lock) { pcmBuffer.toByteArray() }
        return if (pcm.size < 3_200) null else buildWav(pcm)
    }

    private fun buildWav(pcm: ByteArray): ByteArray {
        val byteRate   = SAMPLE_RATE * 2          // mono 16-bit
        val blockAlign = 2
        val bb = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + pcm.size)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)                        // PCM
        bb.putShort(1)                        // mono
        bb.putInt(SAMPLE_RATE)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(16)                       // bits per sample
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(pcm.size)
        bb.put(pcm)
        return bb.array()
    }

    private fun peakAmplitude(buf: ByteArray, n: Int): Float {
        var peak = 0; var i = 0
        while (i + 1 < n) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val a = Math.abs(s.toShort().toInt())
            if (a > peak) peak = a
            i += 2
        }
        return (peak / 32767f).coerceIn(0f, 1f)
    }
}
