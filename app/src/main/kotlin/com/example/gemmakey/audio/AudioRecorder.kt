package com.example.gemmakey.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records 16-bit mono PCM at 16 kHz into an unbounded in-memory buffer.
 *
 * The recording loop runs on a background thread started by [startRecording] so
 * that AudioRecord's internal circular buffer is drained continuously — without
 * this active drain, any recording longer than ~2 s would silently overflow and
 * lose audio data.
 *
 * Max recording duration is capped at [MAX_DURATION_MS] (30 s) to prevent OOM.
 */
class AudioRecorder {

    private val TAG = "AudioRecorder"

    private val SAMPLE_RATE = 16_000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private val MAX_DURATION_MS = 30_000L        // 30-second hard cap
    private val MIN_DURATION_MS = 200L           // ignore taps shorter than 200 ms

    private val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
    // Read in ~100 ms chunks (3 200 shorts); keep internal buffer at 2× chunk size
    private val chunkSamples = SAMPLE_RATE / 10
    private val bufferSize = maxOf(minBuf, chunkSamples * 2 * Short.SIZE_BYTES)

    @Volatile private var recorder: AudioRecord? = null
    private val _isRecording = AtomicBoolean(false)
    val isRecording: Boolean get() = _isRecording.get()

    // Written exclusively by the drain thread; read by stopAndGet() after the thread exits.
    private val audioBuffer = ByteArrayOutputStream()
    private var recordStartMs = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts recording and launches a background drain thread.
     * No-op if already recording.
     */
    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (_isRecording.getAndSet(true)) return

        audioBuffer.reset()
        recordStartMs = System.currentTimeMillis()

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            rec.release()
            _isRecording.set(false)
            return
        }
        recorder = rec
        rec.startRecording()

        // Drain thread: reads PCM chunks into audioBuffer until recording stops
        // or the 30-second cap is reached.
        Thread({
            val chunk = ShortArray(chunkSamples)
            try {
                while (_isRecording.get()) {
                    val elapsed = System.currentTimeMillis() - recordStartMs
                    if (elapsed >= MAX_DURATION_MS) {
                        Log.w(TAG, "Max recording duration reached (${MAX_DURATION_MS} ms)")
                        _isRecording.set(false)
                        break
                    }
                    val read = rec.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        val bytes = shortsToBytes(chunk, read)
                        synchronized(audioBuffer) { audioBuffer.write(bytes) }
                    }
                }
            } finally {
                rec.stop()
                rec.release()
                recorder = null
                Log.d(TAG, "Drain thread exited")
            }
        }, "AudioRecorderDrain").also { it.isDaemon = true; it.start() }

        Log.d(TAG, "Recording started at $SAMPLE_RATE Hz")
    }

    /**
     * Signals the drain thread to stop, waits briefly for it to flush remaining
     * samples, then returns all captured audio as a [ShortArray].
     *
     * Returns an empty array if the recording was shorter than [MIN_DURATION_MS].
     */
    suspend fun stopAndGet(): ShortArray = withContext(Dispatchers.IO) {
        if (!_isRecording.getAndSet(false)) return@withContext ShortArray(0)

        val elapsed = System.currentTimeMillis() - recordStartMs

        // Give the drain thread one extra chunk period to flush
        delay(120L)

        if (elapsed < MIN_DURATION_MS) {
            Log.d(TAG, "Recording too short ($elapsed ms), discarding")
            synchronized(audioBuffer) { audioBuffer.reset() }
            return@withContext ShortArray(0)
        }

        val bytes = synchronized(audioBuffer) {
            val b = audioBuffer.toByteArray()
            audioBuffer.reset()
            b
        }
        Log.d(TAG, "Captured ${bytes.size / 2} samples (${elapsed} ms)")
        bytesToShorts(bytes)
    }

    fun release() {
        _isRecording.set(false)
        // recorder is released by the drain thread; just null our reference
        recorder = null
        synchronized(audioBuffer) { audioBuffer.reset() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buf.putShort(shorts[i])
        return buf.array()
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buf.getShort() }
    }

    /** Converts a PCM short array to a raw byte array (little-endian). */
    fun toByteArray(samples: ShortArray): ByteArray = shortsToBytes(samples, samples.size)
}
