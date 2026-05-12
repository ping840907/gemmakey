package com.example.gemmakey.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records 16-bit mono PCM audio at 16 kHz — the sample rate expected by most
 * on-device speech models.
 *
 * Usage:
 *   recorder.startRecording()
 *   … user holds mic button …
 *   val pcm = recorder.stopAndGet()   // returns short array, clears buffer
 */
class AudioRecorder {

    private val TAG = "AudioRecorder"

    private val SAMPLE_RATE = 16_000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    private val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
    private val bufferSize = maxOf(minBuf, SAMPLE_RATE * 2)  // at least 1 second capacity

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    private val accumulatedSamples = mutableListOf<ShortArray>()

    val isRecording get() = recording

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recording) return
        accumulatedSamples.clear()

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            bufferSize
        )
        recorder = rec
        rec.startRecording()
        recording = true
        Log.d(TAG, "Recording started at ${SAMPLE_RATE} Hz")
    }

    /**
     * Reads all buffered audio from [AudioRecord], stops the recorder, clears
     * internal state, and returns the samples as a [ShortArray].
     */
    suspend fun stopAndGet(): ShortArray = withContext(Dispatchers.IO) {
        val rec = recorder ?: return@withContext ShortArray(0)
        recording = false

        // Drain remaining samples
        val chunk = ShortArray(bufferSize / 2)
        var read: Int
        while (rec.read(chunk, 0, chunk.size, AudioRecord.READ_NON_BLOCKING).also { read = it } > 0) {
            accumulatedSamples.add(chunk.copyOf(read))
        }

        rec.stop()
        rec.release()
        recorder = null
        Log.d(TAG, "Recording stopped")

        val result = merge(accumulatedSamples)
        accumulatedSamples.clear()
        result
    }

    /** Reads a chunk during active recording — call on a background thread. */
    fun readChunk(): ShortArray {
        val rec = recorder ?: return ShortArray(0)
        val chunk = ShortArray(bufferSize / 2)
        val read = rec.read(chunk, 0, chunk.size)
        if (read > 0) accumulatedSamples.add(chunk.copyOf(read))
        return if (read > 0) chunk.copyOf(read) else ShortArray(0)
    }

    fun release() {
        recording = false
        recorder?.apply { stop(); release() }
        recorder = null
        accumulatedSamples.clear()
    }

    private fun merge(chunks: List<ShortArray>): ShortArray {
        val total = chunks.sumOf { it.size }
        val out = ShortArray(total)
        var pos = 0
        for (c in chunks) { c.copyInto(out, pos); pos += c.size }
        return out
    }

    /** Converts a PCM short array to a raw byte array (little-endian). */
    fun toByteArray(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }
}
