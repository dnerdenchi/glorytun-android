package com.example.glorytun

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.example.glorytun.GlorytunConstants.BW_DEFAULT_DAILY_LIMIT_MB
import com.example.glorytun.GlorytunConstants.BW_DEFAULT_MONTHLY_LIMIT_GB
import com.example.glorytun.GlorytunConstants.BW_DEFAULT_THROTTLE_MBPS
import com.example.glorytun.GlorytunConstants.KEY_SIM_DAILY_ENABLED
import com.example.glorytun.GlorytunConstants.KEY_SIM_DAILY_LIMIT_MB
import com.example.glorytun.GlorytunConstants.KEY_SIM_DAILY_THROTTLE
import com.example.glorytun.GlorytunConstants.KEY_SIM_MONTHLY_ENABLED
import com.example.glorytun.GlorytunConstants.KEY_SIM_MONTHLY_LIMIT_GB
import com.example.glorytun.GlorytunConstants.KEY_SIM_MONTHLY_THROTTLE
import com.example.glorytun.GlorytunConstants.KEY_WIFI_DAILY_ENABLED
import com.example.glorytun.GlorytunConstants.KEY_WIFI_DAILY_LIMIT_MB
import com.example.glorytun.GlorytunConstants.KEY_WIFI_DAILY_THROTTLE
import com.example.glorytun.GlorytunConstants.KEY_WIFI_MONTHLY_ENABLED
import com.example.glorytun.GlorytunConstants.KEY_WIFI_MONTHLY_LIMIT_GB
import com.example.glorytun.GlorytunConstants.KEY_WIFI_MONTHLY_THROTTLE
import com.example.glorytun.GlorytunConstants.PREFS_BANDWIDTH

class BandwidthFragment : Fragment() {

    // WiFi 月間
    private lateinit var switchWifiMonthly:       SwitchMaterial
    private lateinit var tvWifiMonthlySummary:    TextView
    private lateinit var panelWifiMonthly:        LinearLayout
    private lateinit var tvWifiMonthlyLimit:      TextView
    private lateinit var seekWifiMonthlyLimit:    SeekBar
    private lateinit var tvWifiMonthlyThrottle:   TextView
    private lateinit var seekWifiMonthlyThrottle: SeekBar

    // WiFi 1日
    private lateinit var switchWifiDaily:         SwitchMaterial
    private lateinit var tvWifiDailySummary:      TextView
    private lateinit var panelWifiDaily:          LinearLayout
    private lateinit var tvWifiDailyLimit:        TextView
    private lateinit var seekWifiDailyLimit:      SeekBar
    private lateinit var tvWifiDailyThrottle:     TextView
    private lateinit var seekWifiDailyThrottle:   SeekBar

    // SIM 月間
    private lateinit var switchSimMonthly:        SwitchMaterial
    private lateinit var tvSimMonthlySummary:     TextView
    private lateinit var panelSimMonthly:         LinearLayout
    private lateinit var tvSimMonthlyLimit:       TextView
    private lateinit var seekSimMonthlyLimit:     SeekBar
    private lateinit var tvSimMonthlyThrottle:    TextView
    private lateinit var seekSimMonthlyThrottle:  SeekBar

    // SIM 1日
    private lateinit var switchSimDaily:          SwitchMaterial
    private lateinit var tvSimDailySummary:       TextView
    private lateinit var panelSimDaily:           LinearLayout
    private lateinit var tvSimDailyLimit:         TextView
    private lateinit var seekSimDailyLimit:       SeekBar
    private lateinit var tvSimDailyThrottle:      TextView
    private lateinit var seekSimDailyThrottle:    SeekBar

    // 現在値
    private var wifiMonthlyEnabled  = false
    private var wifiMonthlyLimitGb  = BW_DEFAULT_MONTHLY_LIMIT_GB
    private var wifiMonthlyThrottle = BW_DEFAULT_THROTTLE_MBPS

    private var wifiDailyEnabled    = false
    private var wifiDailyLimitMb    = BW_DEFAULT_DAILY_LIMIT_MB
    private var wifiDailyThrottle   = BW_DEFAULT_THROTTLE_MBPS

    private var simMonthlyEnabled   = false
    private var simMonthlyLimitGb   = BW_DEFAULT_MONTHLY_LIMIT_GB
    private var simMonthlyThrottle  = BW_DEFAULT_THROTTLE_MBPS

    private var simDailyEnabled     = false
    private var simDailyLimitMb     = BW_DEFAULT_DAILY_LIMIT_MB
    private var simDailyThrottle    = BW_DEFAULT_THROTTLE_MBPS

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bandwidth, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // WiFi 月間
        switchWifiMonthly       = view.findViewById(R.id.switch_wifi_monthly)
        tvWifiMonthlySummary    = view.findViewById(R.id.tv_wifi_monthly_summary)
        panelWifiMonthly        = view.findViewById(R.id.panel_wifi_monthly)
        tvWifiMonthlyLimit      = view.findViewById(R.id.tv_wifi_monthly_limit_value)
        seekWifiMonthlyLimit    = view.findViewById(R.id.seekbar_wifi_monthly_limit)
        tvWifiMonthlyThrottle   = view.findViewById(R.id.tv_wifi_monthly_throttle)
        seekWifiMonthlyThrottle = view.findViewById(R.id.seekbar_wifi_monthly_throttle)

        // WiFi 1日
        switchWifiDaily         = view.findViewById(R.id.switch_wifi_daily)
        tvWifiDailySummary      = view.findViewById(R.id.tv_wifi_daily_summary)
        panelWifiDaily          = view.findViewById(R.id.panel_wifi_daily)
        tvWifiDailyLimit        = view.findViewById(R.id.tv_wifi_daily_limit_value)
        seekWifiDailyLimit      = view.findViewById(R.id.seekbar_wifi_daily_limit)
        tvWifiDailyThrottle     = view.findViewById(R.id.tv_wifi_daily_throttle)
        seekWifiDailyThrottle   = view.findViewById(R.id.seekbar_wifi_daily_throttle)

        // SIM 月間
        switchSimMonthly        = view.findViewById(R.id.switch_sim_monthly)
        tvSimMonthlySummary     = view.findViewById(R.id.tv_sim_monthly_summary)
        panelSimMonthly         = view.findViewById(R.id.panel_sim_monthly)
        tvSimMonthlyLimit       = view.findViewById(R.id.tv_sim_monthly_limit_value)
        seekSimMonthlyLimit     = view.findViewById(R.id.seekbar_sim_monthly_limit)
        tvSimMonthlyThrottle    = view.findViewById(R.id.tv_sim_monthly_throttle)
        seekSimMonthlyThrottle  = view.findViewById(R.id.seekbar_sim_monthly_throttle)

        // SIM 1日
        switchSimDaily          = view.findViewById(R.id.switch_sim_daily)
        tvSimDailySummary       = view.findViewById(R.id.tv_sim_daily_summary)
        panelSimDaily           = view.findViewById(R.id.panel_sim_daily)
        tvSimDailyLimit         = view.findViewById(R.id.tv_sim_daily_limit_value)
        seekSimDailyLimit       = view.findViewById(R.id.seekbar_sim_daily_limit)
        tvSimDailyThrottle      = view.findViewById(R.id.tv_sim_daily_throttle)
        seekSimDailyThrottle    = view.findViewById(R.id.seekbar_sim_daily_throttle)

        loadSettings()
        applyUi()
        setupListeners()

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<LinearLayout>(R.id.btn_save).setOnClickListener {
            saveSettings()
        }
    }

    private fun setupListeners() {
        switchWifiMonthly.setOnCheckedChangeListener { _, checked ->
            wifiMonthlyEnabled = checked
            panelWifiMonthly.visibility = if (checked) View.VISIBLE else View.GONE
            updateWifiMonthlySummary()
        }

        switchWifiDaily.setOnCheckedChangeListener { _, checked ->
            wifiDailyEnabled = checked
            panelWifiDaily.visibility = if (checked) View.VISIBLE else View.GONE
            updateWifiDailySummary()
        }

        switchSimMonthly.setOnCheckedChangeListener { _, checked ->
            simMonthlyEnabled = checked
            panelSimMonthly.visibility = if (checked) View.VISIBLE else View.GONE
            updateSimMonthlySummary()
        }

        switchSimDaily.setOnCheckedChangeListener { _, checked ->
            simDailyEnabled = checked
            panelSimDaily.visibility = if (checked) View.VISIBLE else View.GONE
            updateSimDailySummary()
        }

        seekWifiMonthlyLimit.setOnSeekBarChangeListener(simpleSeekBar { p ->
            wifiMonthlyLimitGb = p + 1
            tvWifiMonthlyLimit.text = "$wifiMonthlyLimitGb GB"
            updateWifiMonthlySummary()
        })

        seekWifiMonthlyThrottle.setOnSeekBarChangeListener(simpleSeekBar { p ->
            wifiMonthlyThrottle = p + 1
            tvWifiMonthlyThrottle.text = "$wifiMonthlyThrottle Mbps"
            updateWifiMonthlySummary()
        })

        seekWifiDailyLimit.setOnSeekBarChangeListener(simpleSeekBar { p ->
            wifiDailyLimitMb = (p + 1) * 100
            tvWifiDailyLimit.text = formatMb(wifiDailyLimitMb)
            updateWifiDailySummary()
        })

        seekWifiDailyThrottle.setOnSeekBarChangeListener(simpleSeekBar { p ->
            wifiDailyThrottle = p + 1
            tvWifiDailyThrottle.text = "$wifiDailyThrottle Mbps"
            updateWifiDailySummary()
        })

        seekSimMonthlyLimit.setOnSeekBarChangeListener(simpleSeekBar { p ->
            simMonthlyLimitGb = p + 1
            tvSimMonthlyLimit.text = "$simMonthlyLimitGb GB"
            updateSimMonthlySummary()
        })

        seekSimMonthlyThrottle.setOnSeekBarChangeListener(simpleSeekBar { p ->
            simMonthlyThrottle = p + 1
            tvSimMonthlyThrottle.text = "$simMonthlyThrottle Mbps"
            updateSimMonthlySummary()
        })

        seekSimDailyLimit.setOnSeekBarChangeListener(simpleSeekBar { p ->
            simDailyLimitMb = (p + 1) * 100
            tvSimDailyLimit.text = formatMb(simDailyLimitMb)
            updateSimDailySummary()
        })

        seekSimDailyThrottle.setOnSeekBarChangeListener(simpleSeekBar { p ->
            simDailyThrottle = p + 1
            tvSimDailyThrottle.text = "$simDailyThrottle Mbps"
            updateSimDailySummary()
        })
    }

    private fun applyUi() {
        switchWifiMonthly.isChecked = wifiMonthlyEnabled
        panelWifiMonthly.visibility = if (wifiMonthlyEnabled) View.VISIBLE else View.GONE
        seekWifiMonthlyLimit.progress    = (wifiMonthlyLimitGb - 1).coerceIn(0, seekWifiMonthlyLimit.max)
        seekWifiMonthlyThrottle.progress = (wifiMonthlyThrottle - 1).coerceIn(0, seekWifiMonthlyThrottle.max)
        tvWifiMonthlyLimit.text    = "$wifiMonthlyLimitGb GB"
        tvWifiMonthlyThrottle.text = "$wifiMonthlyThrottle Mbps"
        updateWifiMonthlySummary()

        switchWifiDaily.isChecked = wifiDailyEnabled
        panelWifiDaily.visibility = if (wifiDailyEnabled) View.VISIBLE else View.GONE
        seekWifiDailyLimit.progress    = (wifiDailyLimitMb / 100 - 1).coerceIn(0, seekWifiDailyLimit.max)
        seekWifiDailyThrottle.progress = (wifiDailyThrottle - 1).coerceIn(0, seekWifiDailyThrottle.max)
        tvWifiDailyLimit.text    = formatMb(wifiDailyLimitMb)
        tvWifiDailyThrottle.text = "$wifiDailyThrottle Mbps"
        updateWifiDailySummary()

        switchSimMonthly.isChecked = simMonthlyEnabled
        panelSimMonthly.visibility = if (simMonthlyEnabled) View.VISIBLE else View.GONE
        seekSimMonthlyLimit.progress    = (simMonthlyLimitGb - 1).coerceIn(0, seekSimMonthlyLimit.max)
        seekSimMonthlyThrottle.progress = (simMonthlyThrottle - 1).coerceIn(0, seekSimMonthlyThrottle.max)
        tvSimMonthlyLimit.text    = "$simMonthlyLimitGb GB"
        tvSimMonthlyThrottle.text = "$simMonthlyThrottle Mbps"
        updateSimMonthlySummary()

        switchSimDaily.isChecked = simDailyEnabled
        panelSimDaily.visibility = if (simDailyEnabled) View.VISIBLE else View.GONE
        seekSimDailyLimit.progress    = (simDailyLimitMb / 100 - 1).coerceIn(0, seekSimDailyLimit.max)
        seekSimDailyThrottle.progress = (simDailyThrottle - 1).coerceIn(0, seekSimDailyThrottle.max)
        tvSimDailyLimit.text    = formatMb(simDailyLimitMb)
        tvSimDailyThrottle.text = "$simDailyThrottle Mbps"
        updateSimDailySummary()
    }

    private fun updateWifiMonthlySummary() {
        tvWifiMonthlySummary.text =
            if (wifiMonthlyEnabled) "$wifiMonthlyLimitGb GB 超過後 ${wifiMonthlyThrottle} Mbps に制限"
            else "無効"
    }

    private fun updateWifiDailySummary() {
        tvWifiDailySummary.text =
            if (wifiDailyEnabled) "${formatMb(wifiDailyLimitMb)} 超過後 ${wifiDailyThrottle} Mbps に制限"
            else "無効"
    }

    private fun updateSimMonthlySummary() {
        tvSimMonthlySummary.text =
            if (simMonthlyEnabled) "$simMonthlyLimitGb GB 超過後 ${simMonthlyThrottle} Mbps に制限"
            else "無効"
    }

    private fun updateSimDailySummary() {
        tvSimDailySummary.text =
            if (simDailyEnabled) "${formatMb(simDailyLimitMb)} 超過後 ${simDailyThrottle} Mbps に制限"
            else "無効"
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_BANDWIDTH, Context.MODE_PRIVATE)

        wifiMonthlyEnabled  = prefs.getBoolean(KEY_WIFI_MONTHLY_ENABLED, false)
        wifiMonthlyLimitGb  = prefs.getInt(KEY_WIFI_MONTHLY_LIMIT_GB, BW_DEFAULT_MONTHLY_LIMIT_GB).coerceIn(1, 200)
        wifiMonthlyThrottle = prefs.getInt(KEY_WIFI_MONTHLY_THROTTLE,  BW_DEFAULT_THROTTLE_MBPS).coerceIn(1, 100)

        wifiDailyEnabled    = prefs.getBoolean(KEY_WIFI_DAILY_ENABLED, false)
        wifiDailyLimitMb    = prefs.getInt(KEY_WIFI_DAILY_LIMIT_MB,    BW_DEFAULT_DAILY_LIMIT_MB).coerceIn(100, 10000)
        wifiDailyThrottle   = prefs.getInt(KEY_WIFI_DAILY_THROTTLE,    BW_DEFAULT_THROTTLE_MBPS).coerceIn(1, 100)

        simMonthlyEnabled   = prefs.getBoolean(KEY_SIM_MONTHLY_ENABLED, false)
        simMonthlyLimitGb   = prefs.getInt(KEY_SIM_MONTHLY_LIMIT_GB, BW_DEFAULT_MONTHLY_LIMIT_GB).coerceIn(1, 200)
        simMonthlyThrottle  = prefs.getInt(KEY_SIM_MONTHLY_THROTTLE,  BW_DEFAULT_THROTTLE_MBPS).coerceIn(1, 100)

        simDailyEnabled     = prefs.getBoolean(KEY_SIM_DAILY_ENABLED, false)
        simDailyLimitMb     = prefs.getInt(KEY_SIM_DAILY_LIMIT_MB,    BW_DEFAULT_DAILY_LIMIT_MB).coerceIn(100, 10000)
        simDailyThrottle    = prefs.getInt(KEY_SIM_DAILY_THROTTLE,    BW_DEFAULT_THROTTLE_MBPS).coerceIn(1, 100)
    }

    private fun saveSettings() {
        requireContext().getSharedPreferences(PREFS_BANDWIDTH, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIFI_MONTHLY_ENABLED,  wifiMonthlyEnabled)
            .putInt(KEY_WIFI_MONTHLY_LIMIT_GB,     wifiMonthlyLimitGb)
            .putInt(KEY_WIFI_MONTHLY_THROTTLE,     wifiMonthlyThrottle)
            .putBoolean(KEY_WIFI_DAILY_ENABLED,    wifiDailyEnabled)
            .putInt(KEY_WIFI_DAILY_LIMIT_MB,       wifiDailyLimitMb)
            .putInt(KEY_WIFI_DAILY_THROTTLE,       wifiDailyThrottle)
            .putBoolean(KEY_SIM_MONTHLY_ENABLED,   simMonthlyEnabled)
            .putInt(KEY_SIM_MONTHLY_LIMIT_GB,      simMonthlyLimitGb)
            .putInt(KEY_SIM_MONTHLY_THROTTLE,      simMonthlyThrottle)
            .putBoolean(KEY_SIM_DAILY_ENABLED,     simDailyEnabled)
            .putInt(KEY_SIM_DAILY_LIMIT_MB,        simDailyLimitMb)
            .putInt(KEY_SIM_DAILY_THROTTLE,        simDailyThrottle)
            .apply()

        Toast.makeText(requireContext(), "保存しました", Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }

    private fun simpleSeekBar(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = onChange(p)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun formatMb(mb: Int): String =
        if (mb < 1000) "$mb MB" else "%.1f GB".format(mb / 1000f)
}
