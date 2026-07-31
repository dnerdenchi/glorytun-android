// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.example.glorytun

import com.mqvpn.sdk.core.model.PathInfo

/** One 1 s chart sample: total and per-interface throughput in bits per second. */
data class BandwidthSample(
    val totalBps: Long,
    val perPathBps: Map<String, Long>,
)

/** History plus stable per-interface palette slots, published together. */
data class BandwidthHistoryState(
    val samples: List<BandwidthSample> = emptyList(),
    val ifaceSlots: Map<String, Int> = emptyMap(),
)

/**
 * Official mqvpn Android bandwidth sampler.
 *
 * Converts cumulative PathInfo byte counters into a rolling window of
 * per-second bps samples using a monotonic clock.
 */
class BandwidthHistory(private val maxSamples: Int = MAX_SAMPLES) {
    private var lastNanos: Long? = null
    private val baselines = mutableMapOf<String, Long>()
    private val slots = mutableMapOf<String, Int>()
    private val samples = ArrayDeque<BandwidthSample>()

    fun onTick(paths: List<PathInfo>, nowNanos: Long): List<BandwidthSample> {
        val previousNanos = lastNanos
        if (previousNanos != null && nowNanos <= previousNanos) return samples.toList()

        val bytesByInterface = paths
            .groupBy { it.iface }
            .mapValues { (_, interfacePaths) ->
                interfacePaths.sumOf { it.bytesTx + it.bytesRx }
            }

        val perPath = mutableMapOf<String, Long>()
        for ((iface, bytes) in bytesByInterface) {
            slots.getOrPut(iface) { slots.size }
            val baseline = baselines[iface]
            perPath[iface] =
                if (previousNanos == null || baseline == null || bytes < baseline) {
                    0L
                } else {
                    ((bytes - baseline) * 8.0 * NANOS_PER_SECOND /
                        (nowNanos - previousNanos)).toLong()
                }
            baselines[iface] = bytes
        }
        baselines.keys.retainAll(bytesByInterface.keys)
        lastNanos = nowNanos

        samples.addLast(BandwidthSample(perPath.values.sum(), perPath))
        while (samples.size > maxSamples) samples.removeFirst()
        return samples.toList()
    }

    fun ifaceSlots(): Map<String, Int> = slots.toMap()

    fun clear() {
        lastNanos = null
        baselines.clear()
        slots.clear()
        samples.clear()
    }

    companion object {
        const val MAX_SAMPLES = 60
        private const val NANOS_PER_SECOND = 1e9
    }
}
