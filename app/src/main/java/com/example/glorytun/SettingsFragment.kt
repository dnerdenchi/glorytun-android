package com.example.glorytun

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<LinearLayout>(R.id.menu_network_protocol)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, NetworkProtocolFragment(), "network_protocol")
                .addToBackStack("network_protocol")
                .commit()
        }

        // 現在のモードをサブタイトルに反映
        updateNetworkModeSubtitle(view)

        view.findViewById<LinearLayout>(R.id.menu_bandwidth)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, BandwidthFragment(), "bandwidth")
                .addToBackStack("bandwidth")
                .commit()
        }

        view.findViewById<LinearLayout>(R.id.menu_speed_test)?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SpeedTestFragment(), "speed_test")
                .addToBackStack("speed_test")
                .commit()
        }

        updateBandwidthSubtitle(view)
        setupUpdateControls(view)

        val proxyPref = requireContext().getSharedPreferences(GlorytunConstants.PREFS_PROXY, Context.MODE_PRIVATE)
        val switchProxy = view.findViewById<SwitchMaterial>(R.id.switch_adguard_proxy)
        val proxyPort = proxyPref.getInt(
            GlorytunConstants.KEY_ADGUARD_PROXY_PORT,
            GlorytunConstants.DEFAULT_ADGUARD_PROXY_PORT
        )
        switchProxy.isChecked = proxyPref.getBoolean(GlorytunConstants.KEY_ADGUARD_PROXY_MODE_ENABLED, false)
        view.findViewById<TextView>(R.id.tv_adguard_proxy_subtitle)?.text =
            if (switchProxy.isChecked) "有効: AdGuard 側で BondVPN を除外し 127.0.0.1:$proxyPort を設定"
            else "AdGuard 側で BondVPN を除外し 127.0.0.1:$proxyPort を設定"
        switchProxy.setOnCheckedChangeListener { _, isChecked ->
            proxyPref.edit().putBoolean(GlorytunConstants.KEY_ADGUARD_PROXY_MODE_ENABLED, isChecked).apply()
            view.findViewById<TextView>(R.id.tv_adguard_proxy_subtitle)?.text =
                if (isChecked) "有効: AdGuard 側で BondVPN を除外し 127.0.0.1:$proxyPort を設定"
                else "AdGuard 側で BondVPN を除外し 127.0.0.1:$proxyPort を設定"
        }

        // ダークモード / ライトモード トグル
        val appearancePref = requireContext().getSharedPreferences(GlorytunConstants.PREFS_APPEARANCE, Context.MODE_PRIVATE)
        val switchDarkMode = view.findViewById<SwitchMaterial>(R.id.switch_dark_mode)
        val isDark = appearancePref.getBoolean(GlorytunConstants.KEY_DARK_MODE_ENABLED, true)
        switchDarkMode.isChecked = isDark
        view.findViewById<TextView>(R.id.tv_dark_mode_subtitle)?.text =
            if (isDark) "ダークモード" else "ライトモード"
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            appearancePref.edit().putBoolean(GlorytunConstants.KEY_DARK_MODE_ENABLED, isChecked).apply()
            view.findViewById<TextView>(R.id.tv_dark_mode_subtitle)?.text =
                if (isChecked) "ダークモード" else "ライトモード"
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun setupUpdateControls(view: View) {
        val updateManager = AppUpdateManager(requireContext().applicationContext)
        val statusText = view.findViewById<TextView>(R.id.tv_update_status)
        val checkButton = view.findViewById<MaterialButton>(R.id.btn_check_update)

        view.findViewById<TextView>(R.id.tv_app_version)?.text = BuildConfig.VERSION_NAME

        checkButton?.setOnClickListener {
            val activity = activity as? AppCompatActivity ?: return@setOnClickListener
            checkButton.isEnabled = false
            statusText?.text = "アップデートを確認しています..."

            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = updateManager.checkForUpdate()) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        statusText?.text = "バージョン ${result.updateInfo.versionName} が利用できます"
                        updateManager.showUpdateDialog(
                            activity,
                            result.updateInfo,
                            viewLifecycleOwner.lifecycleScope
                        )
                    }
                    UpdateCheckResult.NoUpdate -> {
                        statusText?.text = "現在のバージョン ${BuildConfig.VERSION_NAME} が最新版です"
                    }
                    is UpdateCheckResult.Error -> {
                        statusText?.text = result.message
                    }
                }
                checkButton.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let {
            updateNetworkModeSubtitle(it)
            updateBandwidthSubtitle(it)
        }
    }

    private fun updateBandwidthSubtitle(view: View) {
        val prefs = requireContext().getSharedPreferences(GlorytunConstants.PREFS_BANDWIDTH, Context.MODE_PRIVATE)
        val wifiAlways  = prefs.getBoolean(GlorytunConstants.KEY_WIFI_ALWAYS_ENABLED, false)
        val wifiMonthly = prefs.getBoolean(GlorytunConstants.KEY_WIFI_MONTHLY_ENABLED, false)
        val wifiDaily   = prefs.getBoolean(GlorytunConstants.KEY_WIFI_DAILY_ENABLED,   false)
        val simAlways   = prefs.getBoolean(GlorytunConstants.KEY_SIM_ALWAYS_ENABLED, false)
        val simMonthly  = prefs.getBoolean(GlorytunConstants.KEY_SIM_MONTHLY_ENABLED,  false)
        val simDaily    = prefs.getBoolean(GlorytunConstants.KEY_SIM_DAILY_ENABLED,    false)
        val anyEnabled  = wifiAlways || wifiMonthly || wifiDaily || simAlways || simMonthly || simDaily
        val label = if (!anyEnabled) {
            "制限なし"
        } else {
            buildList {
                if (wifiAlways || wifiMonthly || wifiDaily) add("Wi-Fi")
                if (simAlways || simMonthly || simDaily) add("SIM")
            }.joinToString("・") + " 制限 有効"
        }
        view.findViewById<TextView>(R.id.tv_bandwidth_subtitle)?.text = label
    }

    private fun updateNetworkModeSubtitle(view: View) {
        val prefs = requireContext().getSharedPreferences(
            MqvpnRoutingMode.PREFS_NAME, Context.MODE_PRIVATE
        )
        val mode = prefs.getString(MqvpnRoutingMode.KEY_MODE, MqvpnRoutingMode.WLB)
        val label = MqvpnRoutingMode.displayName(mode)
        view.findViewById<android.widget.TextView>(R.id.tv_network_mode_subtitle)?.text = label
    }
}
