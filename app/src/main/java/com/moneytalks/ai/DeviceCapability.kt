package com.moneytalks.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.core.app.ActivityManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeviceCapability"

/**
 * Device-aware resource limits for OOM protection.
 *
 * All thresholds are derived once from total system RAM and the OS low-memory
 * flag, then cached. Components that allocate large objects (bitmaps, LiteRT-LM
 * conversations) use these values instead of hard-coded constants.
 *
 * Tier breakdown:
 *
 *   Tier | Condition              | imagePx | thumbPx | messages | recycleTurns | streamMs
 *   -----|------------------------|---------|---------|----------|--------------|----------
 *     0  | isLowRam OR < 3 GB RAM |   512   |   240   |    40    |      4       |   160
 *     1  | 3–5 GB RAM             |   768   |   320   |    60    |      6       |   100
 *     2  | ≥ 6 GB RAM             |  1024   |   400   |    80    |      8       |    60
 */
@Singleton
class DeviceCapability @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val am: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    val isLowRamDevice: Boolean by lazy { ActivityManagerCompat.isLowRamDevice(am) }

    val totalRamMb: Long by lazy {
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1_048_576L
    }

    val tier: Int by lazy {
        val t = when {
            isLowRamDevice || totalRamMb < 3_072 -> 0
            totalRamMb < 6_144                   -> 1
            else                                 -> 2
        }
        Log.i(TAG, "tier=$t  totalRam=${totalRamMb}MB  isLowRam=$isLowRamDevice")
        t
    }

    /** Max edge pixels for bitmaps passed to inference (camera + gallery). */
    val imageSizePx: Int get() = intArrayOf(512, 768, 1024)[tier]

    /** Max edge pixels for display thumbnails stored in the chat message list. */
    val thumbnailSizePx: Int get() = intArrayOf(240, 320, 400)[tier]

    /** Maximum entries kept in the UI chat list before dropping the oldest. */
    val maxDisplayMessages: Int get() = intArrayOf(40, 60, 80)[tier]

    /** LiteRT-LM Conversation recycle threshold (turns before KV-cache reset). */
    val gemmaRecycleTurns: Int get() = intArrayOf(4, 6, 8)[tier]

    /**
     * Minimum interval (ms) between streaming token UI repaints.
     * Reduces O(n²) String allocations in the collect{} loop on low-end devices.
     */
    val streamUiIntervalMs: Long get() = longArrayOf(160L, 100L, 60L)[tier]

    /**
     * Returns true when the JVM has at least [requiredMb] of free heap.
     *
     * Uses maxMemory() - totalMemory() + freeMemory() which approximates the
     * largest allocation the GC can currently satisfy without an OOM error.
     * Call before allocating large bitmaps or encoding images.
     */
    fun hasHeapFor(requiredMb: Int): Boolean {
        val rt = Runtime.getRuntime()
        val freeMb = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1_048_576L
        return freeMb >= requiredMb
    }
}
