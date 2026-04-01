package com.example.glorytun

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial

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

        updateBandwidthSubtitle(view)

        // AdGuard DNS トグル
        val dnsPref = requireContext().getSharedPreferences(GlorytunConstants.PREFS_DNS, Context.MODE_PRIVATE)
        val switchAdguard = view.findViewById<SwitchMaterial>(R.id.switch_adguard_dns)
        switchAdguard.isChecked = dnsPref.getBoolean(GlorytunConstants.KEY_ADGUARD_DNS_ENABLED, false)
        switchAdguard.setOnCheckedChangeListener { _, isChecked ->
            dnsPref.edit().putBoolean(GlorytunConstants.KEY_ADGUARD_DNS_ENABLED, isChecked).apply()
            view.findViewById<TextView>(R.id.tv_adguard_dns_subtitle)?.text =
                if (isChecked) "有効 (94.140.14.14)" else "広告・トラッカーをDNSでブロック"
        }
        // サブタイトル初期反映
        if (switchAdguard.isChecked) {
            view.findViewById<TextView>(R.id.tv_adguard_dns_subtitle)?.text = "有効 (94.140.14.14)"
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

    override fun onResume() {
        super.onResume()
        view?.let {
            updateNetworkModeSubtitle(it)
            updateBandwidthSubtitle(it)
        }
    }

    private fun updateBandwidthSubtitle(view: View) {
        val prefs = requireContext().getSharedPreferences(GlorytunConstants.PREFS_BANDWIDTH, Context.MODE_PRIVATE)
        val wifiMonthly = prefs.getBoolean(GlorytunConstants.KEY_WIFI_MONTHLY_ENABLED, false)
        val wifiDaily   = prefs.getBoolean(GlorytunConstants.KEY_WIFI_DAILY_ENABLED,   false)
        val simMonthly  = prefs.getBoolean(GlorytunConstants.KEY_SIM_MONTHLY_ENABLED,  false)
        val simDaily    = prefs.getBoolean(GlorytunConstants.KEY_SIM_DAILY_ENABLED,    false)
        val anyEnabled  = wifiMonthly || wifiDaily || simMonthly || simDaily
        val label = if (!anyEnabled) {
            "制限なし"
        } else {
            buildList {
                if (wifiMonthly || wifiDaily) add("WiFi")
                if (simMonthly  || simDaily)  add("SIM")
            }.joinToString("・") + " 制限 有効"
        }
        view.findViewById<TextView>(R.id.tv_bandwidth_subtitle)?.text = label
    }

    private fun updateNetworkModeSubtitle(view: View) {
        val prefs = requireContext().getSharedPreferences(
            NetworkProtocolFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val mode = prefs.getString(NetworkProtocolFragment.KEY_MODE, NetworkProtocolFragment.MODE_BONDING)
        val label = when (mode) {
            NetworkProtocolFragment.MODE_BONDING    -> "ボンディング高速化 (推奨)"
            NetworkProtocolFragment.MODE_WIFI_FIRST -> "WiFi 優先モード"
            NetworkProtocolFragment.MODE_SIM_FIRST  -> "SIM 優先モード"
            else -> "bonding モード・優先順位"
        }
        view.findViewById<android.widget.TextView>(R.id.tv_network_mode_subtitle)?.text = label
    }
}
