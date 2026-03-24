package com.example.glorytun

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class GlorytunVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isConnected = false

    companion object {
        const val ACTION_CONNECT = "com.example.glorytun.START"
        const val ACTION_DISCONNECT = "com.example.glorytun.STOP"
        private const val TAG = "GlorytunVpn"

        // ネイティブラブラリをロード
        init {
            System.loadLibrary("glorytun_jni")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CONNECT) {
            val serverIp = intent.getStringExtra("IP") ?: ""
            val port = intent.getStringExtra("PORT") ?: ""
            val secret = intent.getStringExtra("SECRET") ?: ""
            connectVpn(serverIp, port, secret)
        } else if (action == ACTION_DISCONNECT) {
            disconnectVpn()
        }
        return START_NOT_STICKY
    }

    private fun connectVpn(serverIp: String, port: String, secret: String) {
        if (isConnected) return

        Thread {
            try {
                // VPNインターフェースの設定
                val builder = Builder()
                builder.setSession("Glorytun VPN")
                // クライアントの仮想IPアドレス (環境に合わせて要調整)
                builder.addAddress("10.0.1.2", 24)
                // ルーティング（VPNサブネットのみ、またはサーバー側指定に合わせて）
                builder.addRoute("10.0.1.0", 24)
                // MTU等の設定
                // 1472-byte probe を通せるように、TUN MTU は 1500 を使用する
                builder.setMtu(1500)

                // 外部への接続設定を追加など
                vpnInterface = builder.establish()
                
                if (vpnInterface != null) {
                    isConnected = true
                    val fd = vpnInterface!!.fd
                    Log.i(TAG, "VPN Established, File Descriptor: $fd")
                    
                    sendBroadcast(Intent("VPN_STATE").putExtra("state", "Connected"))

                    // JNI経由でC言語のglorytun関数にFDを渡す
                    startGlorytunNative(fd, serverIp, port, secret)
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN Setup failed", e)
            }
        }.start()
    }

    private fun disconnectVpn() {
        isConnected = false
        stopGlorytunNative()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // handle
        }
        vpnInterface = null
        sendBroadcast(Intent("VPN_STATE").putExtra("state", "Disconnected"))
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectVpn()
    }

    // JNIメソッド宣言
    private external fun startGlorytunNative(fd: Int, ip: String, port: String, secret: String): Int
    private external fun stopGlorytunNative()
}
