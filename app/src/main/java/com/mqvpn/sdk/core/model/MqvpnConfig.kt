// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.core.model

import org.json.JSONArray
import org.json.JSONObject

data class MqvpnConfig(
    val serverAddress: String,
    val serverPort: Int = 443,
    val authKey: String,
    val insecure: Boolean = false,
    val multipathEnabled: Boolean = true,
    val scheduler: Scheduler = Scheduler.MIN_RTT,
    val logLevel: LogLevel = LogLevel.INFO,
    val reconnect: Boolean = true,
    val reconnectIntervalSec: Int = 5,
    val killSwitch: Boolean = false,
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
) {

    enum class Scheduler(val native: Int) {
        MIN_RTT(0),
        WLB(1),
        BACKUP_FEC(2),
        WLB_UDP_PIN(3),
    }

    enum class LogLevel(val native: Int) {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3),
    }

    fun toJson(): String {
        val dns = JSONArray()
        dnsServers.forEach { dns.put(it) }
        return JSONObject()
            .put("serverAddress", serverAddress)
            .put("serverPort", serverPort)
            .put("authKey", authKey)
            .put("insecure", insecure)
            .put("multipathEnabled", multipathEnabled)
            .put("scheduler", scheduler.name)
            .put("logLevel", logLevel.name)
            .put("reconnect", reconnect)
            .put("reconnectIntervalSec", reconnectIntervalSec)
            .put("killSwitch", killSwitch)
            .put("dnsServers", dns)
            .toString()
    }

    companion object {
        fun fromJson(json: String): MqvpnConfig {
            val obj = JSONObject(json)
            return MqvpnConfig(
                serverAddress = obj.getString("serverAddress"),
                serverPort = obj.optInt("serverPort", 443),
                authKey = obj.getString("authKey"),
                insecure = obj.optBoolean("insecure", false),
                multipathEnabled = obj.optBoolean("multipathEnabled", true),
                scheduler = enumValueOfOrDefault(
                    obj.optString("scheduler"),
                    Scheduler.MIN_RTT
                ),
                logLevel = enumValueOfOrDefault(
                    obj.optString("logLevel"),
                    LogLevel.INFO
                ),
                reconnect = obj.optBoolean("reconnect", true),
                reconnectIntervalSec = obj.optInt("reconnectIntervalSec", 5),
                killSwitch = obj.optBoolean("killSwitch", false),
                dnsServers = obj.optJSONArray("dnsServers")?.toStringList()
                    ?: listOf("8.8.8.8", "1.1.1.1")
            )
        }

        private inline fun <reified T : Enum<T>> enumValueOfOrDefault(
            value: String,
            defaultValue: T
        ): T {
            return runCatching { enumValueOf<T>(value) }.getOrDefault(defaultValue)
        }

        private fun JSONArray.toStringList(): List<String> {
            return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
        }
    }
}
