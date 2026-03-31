package com.example.glorytun

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object ServerChecker {

    enum class Status { REACHABLE, UNREACHABLE, CHECKING }

    data class Result(
        val reachable: Boolean,
        val rttMs: Long,
        /** "到達可能", "タイムアウト", "エラー: …" などの詳細文字列 */
        val detail: String
    )

    /**
     * サーバーの到達可能性とRTTを計測する（バックグラウンドスレッドで呼ぶこと）。
     *
     * glorytunはUDPなのでTCP接続は確立されないが、
     * 接続拒否（ECONNREFUSED）が返れば「IPには到達できる」と判定し、
     * その応答時間をRTTの目安として使う。
     */
    fun check(ip: String, port: Int, timeoutMs: Int = 5000): Result {
        val start = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val rtt = System.currentTimeMillis() - start
                Result(true, rtt, "接続確立")
            }
        } catch (e: ConnectException) {
            // ECONNREFUSED: IPには到達できるがTCPポートが閉じている（UDPサービスでは正常）
            val rtt = System.currentTimeMillis() - start
            Result(true, rtt, "到達可能")
        } catch (e: SocketTimeoutException) {
            Result(false, timeoutMs.toLong(), "タイムアウト")
        } catch (e: Exception) {
            val rtt = System.currentTimeMillis() - start
            Result(false, rtt, "エラー: ${e.javaClass.simpleName}")
        }
    }
}
