package com.example.glorytun

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class PairShareTrafficRole {
    SHARING,
    RECEIVING,
}

data class PairShareTrafficSnapshot(
    val activeSessionCount: Int = 0,
    val peerNames: List<String> = emptyList(),
    val sharing: Boolean = false,
    val receiving: Boolean = false,
    val txBytesPerSecond: Long = 0L,
    val rxBytesPerSecond: Long = 0L,
    val receivedBytesPerSecond: Long = 0L,
    val receivingPeerNames: List<String> = emptyList(),
    val sessionTxBytes: Long = 0L,
    val sessionRxBytes: Long = 0L,
    val todayBytes: Long = 0L,
) {
    val active: Boolean get() = activeSessionCount > 0
}

internal data class PairShareRateCounters(
    val sessionId: String,
    val peerName: String,
    val role: PairShareTrafficRole,
    val txBytes: Long,
    val rxBytes: Long,
)

internal data class PairShareRateSample(
    val txBytesPerSecond: Long = 0L,
    val rxBytesPerSecond: Long = 0L,
    val receivedBytesPerSecond: Long = 0L,
    val receivingPeerNames: List<String> = emptyList(),
)

internal class PairShareRateTracker {
    private data class Baseline(val txBytes: Long, val rxBytes: Long)

    private val baselines = mutableMapOf<String, Baseline>()

    fun sample(
        sessions: Collection<PairShareRateCounters>,
        elapsedMillis: Long,
    ): PairShareRateSample {
        val elapsed = elapsedMillis.coerceAtLeast(1L)
        var txDelta = 0L
        var rxDelta = 0L
        var receivedDelta = 0L
        val receivingPeers = linkedSetOf<String>()

        sessions.forEach { session ->
            val baseline = baselines[session.sessionId] ?: Baseline(0L, 0L)
            val sessionTxDelta = (session.txBytes - baseline.txBytes).coerceAtLeast(0L)
            val sessionRxDelta = (session.rxBytes - baseline.rxBytes).coerceAtLeast(0L)
            txDelta += sessionTxDelta
            rxDelta += sessionRxDelta
            if (session.role == PairShareTrafficRole.RECEIVING) {
                receivedDelta += sessionTxDelta + sessionRxDelta
                receivingPeers += session.peerName
            }
            baselines[session.sessionId] = Baseline(session.txBytes, session.rxBytes)
        }
        baselines.keys.retainAll(sessions.mapTo(mutableSetOf(), PairShareRateCounters::sessionId))

        return PairShareRateSample(
            txBytesPerSecond = txDelta * 1_000L / elapsed,
            rxBytesPerSecond = rxDelta * 1_000L / elapsed,
            receivedBytesPerSecond = receivedDelta * 1_000L / elapsed,
            receivingPeerNames = receivingPeers.toList(),
        )
    }
}

data class PairShareUsagePoint(
    val hourStartMillis: Long,
    val txBytes: Long,
    val rxBytes: Long,
) {
    val totalBytes: Long get() = txBytes + rxBytes
}

data class PairShareUsageTotal(
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
) {
    val totalBytes: Long get() = txBytes + rxBytes
}

internal class PairShareUsageHistory(
    points: Collection<PairShareUsagePoint> = emptyList(),
    private val maxPoints: Int = MAX_POINTS,
) {
    private val pointsByHour = points.associateByTo(sortedMapOf()) { it.hourStartMillis }

    fun add(timestampMillis: Long, txBytes: Long, rxBytes: Long) {
        val safeTx = txBytes.coerceAtLeast(0L)
        val safeRx = rxBytes.coerceAtLeast(0L)
        if (safeTx == 0L && safeRx == 0L) return
        val hour = timestampMillis.floorToHour()
        val current = pointsByHour[hour]
        pointsByHour[hour] = PairShareUsagePoint(
            hourStartMillis = hour,
            txBytes = (current?.txBytes ?: 0L) + safeTx,
            rxBytes = (current?.rxBytes ?: 0L) + safeRx,
        )
        while (pointsByHour.size > maxPoints) pointsByHour.remove(pointsByHour.firstKey())
    }

    fun totalSince(cutoffMillis: Long): PairShareUsageTotal {
        var tx = 0L
        var rx = 0L
        pointsByHour.tailMap(cutoffMillis.floorToHour()).values.forEach { point ->
            tx += point.txBytes
            rx += point.rxBytes
        }
        return PairShareUsageTotal(tx, rx)
    }

    fun points(): List<PairShareUsagePoint> = pointsByHour.values.toList()

    companion object {
        const val MAX_POINTS = 8_784
    }
}

private class PairShareUsageStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val history = PairShareUsageHistory(load())

    @Synchronized
    fun add(timestampMillis: Long, txBytes: Long, rxBytes: Long) {
        history.add(timestampMillis, txBytes, rxBytes)
    }

    @Synchronized
    fun totalSince(cutoffMillis: Long): PairShareUsageTotal = history.totalSince(cutoffMillis)

    @Synchronized
    fun flush() {
        val array = JSONArray()
        history.points().forEach { point ->
            array.put(
                JSONObject()
                    .put("t", point.hourStartMillis)
                    .put("tx", point.txBytes)
                    .put("rx", point.rxBytes),
            )
        }
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (_: Throwable) {
            output?.let(file::failWrite)
        }
    }

    private fun load(): List<PairShareUsagePoint> {
        if (!file.baseFile.exists()) return emptyList()
        return runCatching {
            val raw = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        PairShareUsagePoint(
                            hourStartMillis = item.optLong("t").floorToHour(),
                            txBytes = item.optLong("tx").coerceAtLeast(0L),
                            rxBytes = item.optLong("rx").coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val FILE_NAME = "pair_share_usage.json"
    }
}

/** Process-wide live and persisted accounting for Pair & Share payload traffic. */
object PairShareTrafficMonitor {
    private data class Session(
        val peerName: String,
        val role: PairShareTrafficRole,
        var txBytes: Long = 0L,
        var rxBytes: Long = 0L,
        var accountedTxBytes: Long = 0L,
        var accountedRxBytes: Long = 0L,
    )

    private val lock = Any()
    private val sessions = linkedMapOf<String, Session>()
    private val mutableState = MutableLiveData(PairShareTrafficSnapshot())
    val state: LiveData<PairShareTrafficSnapshot> = mutableState
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "pair-share-traffic").apply { isDaemon = true }
    }
    private val rateTracker = PairShareRateTracker()

    @Volatile private var store: PairShareUsageStore? = null
    private var lastSampleElapsed = SystemClock.elapsedRealtime()
    private var lastFlushElapsed = lastSampleElapsed
    private var pendingTxBytes = 0L
    private var pendingRxBytes = 0L

    init {
        scheduler.scheduleAtFixedRate(::tick, 1L, 1L, TimeUnit.SECONDS)
    }

    fun initialize(context: Context) {
        if (store != null) return
        synchronized(lock) {
            if (store == null) store = PairShareUsageStore(context.applicationContext)
            publishLocked(idleRateLocked())
        }
    }

    fun newSessionId(prefix: String): String = "$prefix:${UUID.randomUUID()}"

    fun startSession(
        context: Context,
        sessionId: String,
        peerName: String,
        role: PairShareTrafficRole,
    ) {
        initialize(context)
        synchronized(lock) {
            if (sessions.isEmpty()) lastSampleElapsed = SystemClock.elapsedRealtime()
            sessions[sessionId] = Session(peerName = peerName, role = role)
            publishLocked(idleRateLocked())
        }
    }

    fun recordSent(sessionId: String, bytes: Long) {
        if (bytes <= 0L) return
        synchronized(lock) { sessions[sessionId]?.let { it.txBytes += bytes } }
    }

    fun recordReceived(sessionId: String, bytes: Long) {
        if (bytes <= 0L) return
        synchronized(lock) { sessions[sessionId]?.let { it.rxBytes += bytes } }
    }

    fun endSession(sessionId: String) {
        synchronized(lock) {
            sessions.remove(sessionId)?.let(::accountLocked)
            flushLocked(force = sessions.isEmpty())
            publishLocked(idleRateLocked())
        }
    }

    fun usageSince(context: Context, cutoffMillis: Long): PairShareUsageTotal {
        initialize(context)
        return synchronized(lock) {
            accountAllLocked()
            store?.totalSince(cutoffMillis).orEmpty() + PairShareUsageTotal(pendingTxBytes, pendingRxBytes)
        }
    }

    private fun tick() {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (sessions.isEmpty()) {
                lastSampleElapsed = now
                return
            }
            val elapsed = (now - lastSampleElapsed).coerceAtLeast(1L)
            val rateSample = rateTracker.sample(
                sessions.map { (sessionId, session) ->
                    PairShareRateCounters(
                        sessionId = sessionId,
                        peerName = session.peerName,
                        role = session.role,
                        txBytes = session.txBytes,
                        rxBytes = session.rxBytes,
                    )
                },
                elapsed,
            )
            accountAllLocked()
            flushLocked(force = now - lastFlushElapsed >= FLUSH_INTERVAL_MILLIS)
            publishLocked(rateSample)
            lastSampleElapsed = now
        }
    }

    private fun accountAllLocked() = sessions.values.forEach(::accountLocked)

    private fun accountLocked(session: Session) {
        pendingTxBytes += (session.txBytes - session.accountedTxBytes).coerceAtLeast(0L)
        pendingRxBytes += (session.rxBytes - session.accountedRxBytes).coerceAtLeast(0L)
        session.accountedTxBytes = session.txBytes
        session.accountedRxBytes = session.rxBytes
    }

    private fun flushLocked(force: Boolean) {
        val currentStore = store ?: return
        if (pendingTxBytes == 0L && pendingRxBytes == 0L) return
        if (!force) return
        currentStore.add(System.currentTimeMillis(), pendingTxBytes, pendingRxBytes)
        pendingTxBytes = 0L
        pendingRxBytes = 0L
        currentStore.flush()
        lastFlushElapsed = SystemClock.elapsedRealtime()
    }

    private fun idleRateLocked() = PairShareRateSample(
        receivingPeerNames = sessions.values
            .filter { it.role == PairShareTrafficRole.RECEIVING }
            .map(Session::peerName)
            .distinct(),
    )

    private fun publishLocked(rate: PairShareRateSample) {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val persistedToday = store?.totalSince(todayStart)?.totalBytes ?: 0L
        mutableState.postValue(
            PairShareTrafficSnapshot(
                activeSessionCount = sessions.size,
                peerNames = sessions.values.map(Session::peerName).distinct(),
                sharing = sessions.values.any { it.role == PairShareTrafficRole.SHARING },
                receiving = sessions.values.any { it.role == PairShareTrafficRole.RECEIVING },
                txBytesPerSecond = rate.txBytesPerSecond,
                rxBytesPerSecond = rate.rxBytesPerSecond,
                receivedBytesPerSecond = rate.receivedBytesPerSecond,
                receivingPeerNames = rate.receivingPeerNames,
                sessionTxBytes = sessions.values.sumOf(Session::txBytes),
                sessionRxBytes = sessions.values.sumOf(Session::rxBytes),
                todayBytes = persistedToday + pendingTxBytes + pendingRxBytes,
            ),
        )
    }

    private fun PairShareUsageTotal?.orEmpty(): PairShareUsageTotal = this ?: PairShareUsageTotal()

    private operator fun PairShareUsageTotal.plus(other: PairShareUsageTotal) = PairShareUsageTotal(
        txBytes = txBytes + other.txBytes,
        rxBytes = rxBytes + other.rxBytes,
    )

    private const val FLUSH_INTERVAL_MILLIS = 5_000L
}

private fun Long.floorToHour(): Long = this / 3_600_000L * 3_600_000L
