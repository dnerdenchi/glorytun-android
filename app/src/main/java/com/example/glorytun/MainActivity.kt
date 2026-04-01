package com.example.glorytun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())

    // 未接続時もリアルタイムグラフにゼロ点を追加し続けるRunnable
    private val zeroDataRunnable = object : Runnable {
        override fun run() {
            val state = viewModel.connectionState.value
            if (state != "Connected" && state != "Connecting...") {
                viewModel.addRealtimePoint(0f, 0f)
            }
            handler.postDelayed(this, 1000)
        }
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
            val dailyWifi = intent.getDoubleExtra("daily_wifi_kb", -1.0)
            if (dailyWifi >= 0.0) {
                viewModel.updateDailyTraffic(
                    wifiKB      = dailyWifi,
                    simKB       = intent.getDoubleExtra("daily_sim_kb", 0.0),
                    wThrottled  = intent.getBooleanExtra("wifi_throttled", false),
                    sThrottled  = intent.getBooleanExtra("sim_throttled",  false)
                )
            }
        }
    }

    // 1時間ごとの集計データ保存通知を受け取り、ViewModelのメモリを更新する
    private val hourlyStatsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val point = TrafficPoint(
                timestamp = intent.getLongExtra("timestamp", 0L),
                wifiKBs   = intent.getFloatExtra("wifi_kbs", 0f),
                simKBs    = intent.getFloatExtra("sim_kbs",  0f)
            )
            viewModel.addHistoricalPoint(point)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // テーマ設定をコンテンツ描画前に適用する
        val appearancePref = getSharedPreferences(GlorytunConstants.PREFS_APPEARANCE, Context.MODE_PRIVATE)
        val isDark = appearancePref.getBoolean(GlorytunConstants.KEY_DARK_MODE_ENABLED, true)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (isDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 旧設定が存在する場合はプロファイルに移行
        val legacyIp = prefs.getString("IP", "") ?: ""
        val legacyPort = prefs.getString("PORT", "5000") ?: "5000"
        val legacySecret = prefs.getString("SECRET", "") ?: ""
        val repo = ProfileRepository(this)
        repo.migrateFromLegacy(legacyIp, legacyPort, legacySecret)

        // アクティブプロファイルをViewModelと旧設定ストアに反映する
        val profiles = repo.loadProfiles()
        val activeProfileId = repo.getActiveProfileId()
        val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull()
        if (activeProfile != null) {
            loadProfile(activeProfile)
        } else {
            viewModel.serverIp.value = legacyIp
            viewModel.serverPort.value = legacyPort
        }

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
        ContextCompat.registerReceiver(
            this, hourlyStatsReceiver, IntentFilter("VPN_HOURLY_STATS"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // アプリ起動中はリアルタイムグラフのゼロ点追加を開始
        handler.post(zeroDataRunnable)

        // サービスが既に起動中の場合に接続状態を取得する
        startService(Intent(this, GlorytunVpnService::class.java).apply {
            action = GlorytunConstants.ACTION_QUERY_STATE
        })
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

    /** プロファイルをアクティブ設定として読み込む */
    fun loadProfile(profile: VpnProfile) {
        prefs.edit()
            .putString("IP", profile.ip)
            .putString("PORT", profile.port)
            .putString("SECRET", profile.secret)
            .apply()
        viewModel.serverIp.value = profile.ip
        viewModel.serverPort.value = profile.port
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(zeroDataRunnable)
        // アプリ終了時にリアルタイムグラフデータをリセット
        viewModel.resetRealtimeData()
        unregisterReceiver(stateReceiver)
        unregisterReceiver(trafficReceiver)
        unregisterReceiver(hourlyStatsReceiver)
    }
}
