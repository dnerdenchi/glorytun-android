package com.example.glorytun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.glorytun.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: VpnViewModel by viewModels()

    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "GlorytunPrefs", masterKeyAlias, this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("state")?.let { state ->
                viewModel.connectionState.value = state
                if (state == "Disconnected") {
                    viewModel.reset()
                }
            }
        }
    }

    private val trafficReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            viewModel.updateTraffic(
                wifiActive = intent.getBooleanExtra("wifi_active", false),
                simActive  = intent.getBooleanExtra("sim_active",  false),
                wifiTx     = intent.getLongExtra("wifi_tx_bytes", 0L),
                wifiRx     = intent.getLongExtra("wifi_rx_bytes", 0L),
                simTx      = intent.getLongExtra("sim_tx_bytes",  0L),
                simRx      = intent.getLongExtra("sim_rx_bytes",  0L)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 保存済み設定をViewModelに復元
        viewModel.serverIp.value = prefs.getString("IP", "") ?: ""
        viewModel.serverPort.value = prefs.getString("PORT", "5000") ?: "5000"

        // 初期フラグメントをセット
        if (savedInstanceState == null) {
            showFragment(DashboardFragment(), "dashboard")
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> { showFragment(DashboardFragment(), "dashboard"); true }
                R.id.nav_stats     -> { showFragment(StatsFragment(),     "stats");     true }
                R.id.nav_bonding   -> { showFragment(BondingFragment(),   "bonding");   true }
                R.id.nav_settings  -> { showFragment(SettingsFragment(),  "settings");  true }
                else -> false
            }
        }

        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter("VPN_STATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, trafficReceiver, IntentFilter("VPN_TRAFFIC_STATS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commit()
    }

    fun saveCredentials(ip: String, port: String, secret: String) {
        prefs.edit()
            .putString("IP", ip)
            .putString("PORT", port)
            .putString("SECRET", secret)
            .apply()
        viewModel.serverIp.value = ip
        viewModel.serverPort.value = port
    }

    fun getSecret(): String = prefs.getString("SECRET", "") ?: ""

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
        unregisterReceiver(trafficReceiver)
    }
}
