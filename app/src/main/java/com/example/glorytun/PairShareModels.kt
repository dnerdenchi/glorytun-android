package com.example.glorytun

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** A device that has completed the explicit Pair & Share approval flow. */
data class PairSharePeer(
    val id: String,
    val displayName: String,
    /** Base64-encoded, per-peer key derived during pairing. Stored encrypted at rest. */
    val sharedKey: String,
    val address: String? = null,
    val port: Int = 0,
    /** Whether this local device permits the peer to use this device's BondVPN connection. */
    val canUseMyConnection: Boolean = false,
    /** Zero means unlimited. This is enforced by the sharing device. */
    val speedLimitMbps: Int = DEFAULT_SPEED_LIMIT_MBPS,
    val lastSeenMillis: Long = 0L,
) {
    companion object {
        const val DEFAULT_SPEED_LIMIT_MBPS = 10
    }
}

data class PairShareDiscovery(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val lastSeenMillis: Long,
)

data class PairSharePending(
    val requestId: String,
    val peerId: String,
    val displayName: String,
    val verificationWords: String,
)

data class PairShareUiState(
    val enabled: Boolean = false,
    val serviceStatus: String = "Pair & Share は無効です",
    val pairingCode: String = "------",
    val vpnConnected: Boolean = false,
    val discovered: List<PairShareDiscovery> = emptyList(),
    val peers: List<PairSharePeer> = emptyList(),
    val pending: List<PairSharePending> = emptyList(),
)

/**
 * Encrypted persistence for Pair & Share identities and permissions.
 *
 * Endpoint data is intentionally treated as a hint only: the active Wi-Fi
 * discovery record refreshes it before a peer connection is opened.
 */
class PairShareRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isSharingEnabled(): Boolean = prefs.getBoolean(KEY_SHARING_ENABLED, false)

    fun setSharingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHARING_ENABLED, enabled).apply()
    }

    fun isReceivingEnabled(): Boolean = prefs.getBoolean(KEY_RECEIVING_ENABLED, false)

    fun setReceivingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECEIVING_ENABLED, enabled).apply()
    }

    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun displayName(): String = prefs.getString(KEY_DISPLAY_NAME, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: defaultDeviceName()

    fun setDisplayName(name: String) {
        val normalized = name.trim().take(MAX_DISPLAY_NAME_LENGTH)
        if (normalized.isEmpty()) return
        prefs.edit().putString(KEY_DISPLAY_NAME, normalized).apply()
    }

    fun pairingCode(nowMillis: Long = System.currentTimeMillis()): String {
        val code = prefs.getString(KEY_PAIRING_CODE, null)
        val expiresAt = prefs.getLong(KEY_PAIRING_CODE_EXPIRES_AT, 0L)
        if (code != null && nowMillis < expiresAt) return code
        return regeneratePairingCode(nowMillis)
    }

    fun regeneratePairingCode(nowMillis: Long = System.currentTimeMillis()): String {
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        prefs.edit()
            .putString(KEY_PAIRING_CODE, code)
            .putLong(KEY_PAIRING_CODE_EXPIRES_AT, nowMillis + PAIRING_CODE_TTL_MILLIS)
            .apply()
        return code
    }

    fun peers(): List<PairSharePeer> {
        val raw = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val key = item.optString("key").takeIf { it.isNotBlank() } ?: continue
                    add(
                        PairSharePeer(
                            id = id,
                            displayName = item.optString("name", "BondVPN デバイス"),
                            sharedKey = key,
                            address = item.optString("address").takeIf { it.isNotBlank() },
                            port = item.optInt("port", 0).takeIf { it in 1..65535 } ?: 0,
                            canUseMyConnection = item.optBoolean("canUseMyConnection", false),
                            speedLimitMbps = item.optInt(
                                "speedLimitMbps",
                                PairSharePeer.DEFAULT_SPEED_LIMIT_MBPS,
                            ).coerceIn(0, MAX_SPEED_LIMIT_MBPS),
                            lastSeenMillis = item.optLong("lastSeenMillis", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun peer(id: String): PairSharePeer? = peers().firstOrNull { it.id == id }

    fun upsertPeer(peer: PairSharePeer) {
        val updated = peers().toMutableList()
        val position = updated.indexOfFirst { it.id == peer.id }
        if (position >= 0) updated[position] = peer else updated += peer
        savePeers(updated)
    }

    fun updatePeerEndpoint(id: String, host: String, port: Int, seenAt: Long = System.currentTimeMillis()) {
        val existing = peer(id) ?: return
        upsertPeer(existing.copy(address = host, port = port, lastSeenMillis = seenAt))
    }

    fun setPeerSharePermission(id: String, allowed: Boolean, speedLimitMbps: Int) {
        val existing = peer(id) ?: return
        upsertPeer(
            existing.copy(
                canUseMyConnection = allowed,
                speedLimitMbps = speedLimitMbps.coerceIn(0, MAX_SPEED_LIMIT_MBPS),
            ),
        )
    }

    fun removePeer(id: String) {
        savePeers(peers().filterNot { it.id == id })
        if (activeReceivePeerId() == id) {
            prefs.edit().remove(KEY_ACTIVE_RECEIVE_PEER).apply()
        }
    }

    fun activeReceivePeerId(): String? = prefs.getString(KEY_ACTIVE_RECEIVE_PEER, null)

    fun activeReceivePeer(): PairSharePeer? = activeReceivePeerId()?.let(::peer)

    fun setActiveReceivePeer(id: String?) {
        val editor = prefs.edit()
        if (id == null) editor.remove(KEY_ACTIVE_RECEIVE_PEER)
        else editor.putString(KEY_ACTIVE_RECEIVE_PEER, id)
        editor.apply()
    }

    private fun savePeers(peers: List<PairSharePeer>) {
        val array = JSONArray()
        peers.forEach { peer ->
            array.put(
                JSONObject()
                    .put("id", peer.id)
                    .put("name", peer.displayName)
                    .put("key", peer.sharedKey)
                    .put("address", peer.address ?: "")
                    .put("port", peer.port)
                    .put("canUseMyConnection", peer.canUseMyConnection)
                    .put("speedLimitMbps", peer.speedLimitMbps)
                    .put("lastSeenMillis", peer.lastSeenMillis),
            )
        }
        prefs.edit().putString(KEY_PEERS, array.toString()).apply()
    }

    private fun defaultDeviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER?.trim().orEmpty()
        val model = android.os.Build.MODEL?.trim().orEmpty()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(MAX_DISPLAY_NAME_LENGTH)
            .ifBlank { "BondVPN デバイス" }
    }

    companion object {
        private const val PREFS_NAME = "pair_share_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SHARING_ENABLED = "sharing_enabled"
        private const val KEY_RECEIVING_ENABLED = "receiving_enabled"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_PAIRING_CODE_EXPIRES_AT = "pairing_code_expires_at"
        private const val KEY_PEERS = "peers"
        private const val KEY_ACTIVE_RECEIVE_PEER = "active_receive_peer"

        const val PAIRING_CODE_TTL_MILLIS = 10 * 60 * 1000L
        const val MAX_SPEED_LIMIT_MBPS = 100
        private const val MAX_DISPLAY_NAME_LENGTH = 48
        private val random = SecureRandom()
    }
}

/** Process-local coordination between the foreground service, UI, and proxy service. */
object PairShareCoordinator {
    private val mutableState = MutableLiveData(PairShareUiState())
    val state: LiveData<PairShareUiState> = mutableState

    @Volatile
    private var client: PairShareClient? = null

    @Synchronized
    fun openTcp(context: Context, host: String, port: Int): PairShareTcpStream {
        return clientForActivePeer(context).openTcp(host, port)
    }

    @Synchronized
    fun openUdp(context: Context): PairShareUdpStream {
        return clientForActivePeer(context).openUdp()
    }

    @Synchronized
    fun closeClient() {
        client?.close()
        client = null
    }

    fun publish(state: PairShareUiState) {
        mutableState.postValue(state)
    }

    private fun clientForActivePeer(context: Context): PairShareClient {
        val repository = PairShareRepository(context)
        val peer = repository.activeReceivePeer()
            ?: throw IllegalStateException("受信に使うペア端末を選択してください")
        if (!repository.isReceivingEnabled()) {
            throw IllegalStateException("「受信を許可」をオンにしてください")
        }
        val existing = client
        if (existing != null && existing.matches(peer) && existing.isAlive()) return existing
        existing?.close()
        return PairShareClient(context.applicationContext, peer).also { client = it }
    }
}
