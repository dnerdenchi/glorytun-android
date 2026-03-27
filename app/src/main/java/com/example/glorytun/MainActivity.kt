package com.example.glorytun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.glorytun.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isVpnConnected = false

    // 前回受信したバイト数（スループット計算用）
    private var prevWifiTx = 0L
    private var prevWifiRx = 0L
    private var prevSimTx  = 0L
    private var prevSimRx  = 0L

    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "GlorytunPrefs", masterKeyAlias, this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnConnection()
        } else {
            Toast.makeText(this, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    // VPN接続状態の受信
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("state")?.let { state ->
                binding.tvStatus.text = "Status: $state"
                when (state) {
                    "Connected" -> {
                        isVpnConnected = true
                        binding.btnConnect.text = "Disconnect"
                    }
                    "Disconnected" -> {
                        isVpnConnected = false
                        binding.btnConnect.text = "Connect"
                        resetTrafficStats()
                    }
                }
            }
        }
    }

    // 通信量統計の受信
    private val trafficReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val wifiActive = intent.getBooleanExtra("wifi_active", false)
            val simActive  = intent.getBooleanExtra("sim_active",  false)

            val wifiTx = intent.getLongExtra("wifi_tx_bytes", 0L)
            val wifiRx = intent.getLongExtra("wifi_rx_bytes", 0L)
            val simTx  = intent.getLongExtra("sim_tx_bytes",  0L)
            val simRx  = intent.getLongExtra("sim_rx_bytes",  0L)

            // 前回値からデルタを計算してKB/sに変換（初回は0）
            val wifiKBs = if (wifiActive && prevWifiTx > 0) {
                ((wifiTx - prevWifiTx) + (wifiRx - prevWifiRx)).coerceAtLeast(0L) / 1024f
            } else 0f

            val simKBs = if (simActive && prevSimTx > 0) {
                ((simTx - prevSimTx) + (simRx - prevSimRx)).coerceAtLeast(0L) / 1024f
            } else 0f

            binding.trafficGraph.addDataPoint(wifiKBs, simKBs)

            if (wifiActive) {
                prevWifiTx = wifiTx
                prevWifiRx = wifiRx
                binding.tvWifiStats.text = "WiFi: %.1f KB/s".format(wifiKBs)
            } else {
                prevWifiTx = 0L
                prevWifiRx = 0L
                binding.tvWifiStats.text = "WiFi: --"
            }

            if (simActive) {
                prevSimTx = simTx
                prevSimRx = simRx
                binding.tvSimStats.text = "SIM: %.1f KB/s".format(simKBs)
            } else {
                prevSimTx = 0L
                prevSimRx = 0L
                binding.tvSimStats.text = "SIM: --"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editServerIp.setText(prefs.getString("IP", ""))
        binding.editServerPort.setText(prefs.getString("PORT", "5000"))
        binding.editSecret.setText(prefs.getString("SECRET", ""))

        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter("VPN_STATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, trafficReceiver, IntentFilter("VPN_TRAFFIC_STATS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        binding.btnConnect.setOnClickListener {
            if (isVpnConnected) {
                val serviceIntent = Intent(this, GlorytunVpnService::class.java).apply {
                    action = GlorytunVpnService.ACTION_DISCONNECT
                }
                startService(serviceIntent)
                binding.tvStatus.text = "Status: Disconnecting..."
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    startVpnConnection()
                }
            }
        }
    }

    private fun startVpnConnection() {
        val ip     = binding.editServerIp.text.toString()
        val port   = binding.editServerPort.text.toString()
        val secret = binding.editSecret.text.toString()

        prefs.edit()
            .putString("IP",     ip)
            .putString("PORT",   port)
            .putString("SECRET", secret)
            .apply()

        val serviceIntent = Intent(this, GlorytunVpnService::class.java).apply {
            action = GlorytunVpnService.ACTION_CONNECT
            putExtra("IP",     ip)
            putExtra("PORT",   port)
            putExtra("SECRET", secret)
        }
        startService(serviceIntent)
        binding.tvStatus.text = "Status: Connecting..."
        binding.btnConnect.text = "Disconnect"
    }

    private fun resetTrafficStats() {
        prevWifiTx = 0L; prevWifiRx = 0L
        prevSimTx  = 0L; prevSimRx  = 0L
        binding.tvWifiStats.text = "WiFi: --"
        binding.tvSimStats.text  = "SIM: --"
        binding.trafficGraph.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
        unregisterReceiver(trafficReceiver)
    }
}
