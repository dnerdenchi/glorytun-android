package com.example.glorytun

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

    // ─── 値テーブル ───────────────────────────────────────────
    // 月間上限 (GB単位の整数)
    private val monthlyLimitSteps = intArrayOf(1, 2, 3, 5, 10, 15, 20, 30, 50, 100, 200)
    private val monthlyLimitPresets = intArrayOf(5, 10, 20, 30)   // チップ表示する値

    // 1日上限 (MB単位の整数)
    private val dailyLimitSteps = intArrayOf(100, 200, 500, 1000, 2000, 3000, 5000, 10000)
    private val dailyLimitPresets = intArrayOf(500, 1000, 3000, 5000) // チップ表示する値 (MB)

    // 速度制限 (kbps単位で統一保持: 128, 256, 512, 1000, 2000, 5000, 10000)
    private val throttleSteps = intArrayOf(128, 256, 512, 1000, 2000, 5000, 10000)
    private val throttlePresets = intArrayOf(256, 512, 1000, 5000) // チップ表示する値 (kbps)

    // ─── Views ───────────────────────────────────────────────
    // WiFi 月間
    private lateinit var switchWifiMonthly: SwitchMaterial
    private lateinit var tvWifiMonthlySummary: TextView
    private lateinit var panelWifiMonthly: LinearLayout
    private lateinit var tvWifiMonthlyLimit: TextView
    private lateinit var chipsWifiMonthlyLimit: LinearLayout
    private lateinit var seekWifiMonthlyLimit: SeekBar
    private lateinit var customWifiMonthlyLimit: LinearLayout
    private lateinit var etWifiMonthlyLimit: EditText
    private lateinit var tvWifiMonthlyThrottle: TextView
    private lateinit var chipsWifiMonthlyThrottle: LinearLayout
    private lateinit var seekWifiMonthlyThrottle: SeekBar
    private lateinit var customWifiMonthlyThrottle: LinearLayout
    private lateinit var etWifiMonthlyThrottle: EditText

    // WiFi 1日
    private lateinit var switchWifiDaily: SwitchMaterial
    private lateinit var tvWifiDailySummary: TextView
    private lateinit var panelWifiDaily: LinearLayout
    private lateinit var tvWifiDailyLimit: TextView
    private lateinit var chipsWifiDailyLimit: LinearLayout
    private lateinit var seekWifiDailyLimit: SeekBar
    private lateinit var customWifiDailyLimit: LinearLayout
    private lateinit var etWifiDailyLimit: EditText
    private lateinit var tvWifiDailyThrottle: TextView
    private lateinit var chipsWifiDailyThrottle: LinearLayout
    private lateinit var seekWifiDailyThrottle: SeekBar
    private lateinit var customWifiDailyThrottle: LinearLayout
    private lateinit var etWifiDailyThrottle: EditText

    // SIM 月間
    private lateinit var switchSimMonthly: SwitchMaterial
    private lateinit var tvSimMonthlySummary: TextView
    private lateinit var panelSimMonthly: LinearLayout
    private lateinit var tvSimMonthlyLimit: TextView
    private lateinit var chipsSimMonthlyLimit: LinearLayout
    private lateinit var seekSimMonthlyLimit: SeekBar
    private lateinit var customSimMonthlyLimit: LinearLayout
    private lateinit var etSimMonthlyLimit: EditText
    private lateinit var tvSimMonthlyThrottle: TextView
    private lateinit var chipsSimMonthlyThrottle: LinearLayout
    private lateinit var seekSimMonthlyThrottle: SeekBar
    private lateinit var customSimMonthlyThrottle: LinearLayout
    private lateinit var etSimMonthlyThrottle: EditText

    // SIM 1日
    private lateinit var switchSimDaily: SwitchMaterial
    private lateinit var tvSimDailySummary: TextView
    private lateinit var panelSimDaily: LinearLayout
    private lateinit var tvSimDailyLimit: TextView
    private lateinit var chipsSimDailyLimit: LinearLayout
    private lateinit var seekSimDailyLimit: SeekBar
    private lateinit var customSimDailyLimit: LinearLayout
    private lateinit var etSimDailyLimit: EditText
    private lateinit var tvSimDailyThrottle: TextView
    private lateinit var chipsSimDailyThrottle: LinearLayout
    private lateinit var seekSimDailyThrottle: SeekBar
    private lateinit var customSimDailyThrottle: LinearLayout
    private lateinit var etSimDailyThrottle: EditText

    // ─── 現在値 (保存単位: GB / MB / kbps) ───────────────────
    private var wifiMonthlyEnabled = false
    private var wifiMonthlyLimitGb = BW_DEFAULT_MONTHLY_LIMIT_GB   // GB
    private var wifiMonthlyThrottleKbps = BW_DEFAULT_THROTTLE_MBPS * 1000 // kbps

    private var wifiDailyEnabled = false
    private var wifiDailyLimitMb = BW_DEFAULT_DAILY_LIMIT_MB       // MB
    private var wifiDailyThrottleKbps = BW_DEFAULT_THROTTLE_MBPS * 1000

    private var simMonthlyEnabled = false
    private var simMonthlyLimitGb = BW_DEFAULT_MONTHLY_LIMIT_GB
    private var simMonthlyThrottleKbps = BW_DEFAULT_THROTTLE_MBPS * 1000

    private var simDailyEnabled = false
    private var simDailyLimitMb = BW_DEFAULT_DAILY_LIMIT_MB
    private var simDailyThrottleKbps = BW_DEFAULT_THROTTLE_MBPS * 1000

    // ─── Fragment lifecycle ───────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bandwidth, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        loadSettings()
        buildAllChips()
        applyUi()
        setupListeners()

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<LinearLayout>(R.id.btn_save).setOnClickListener {
            saveSettings()
        }
    }

    // ─── View バインド ────────────────────────────────────────
    private fun bindViews(v: View) {
        switchWifiMonthly       = v.findViewById(R.id.switch_wifi_monthly)
        tvWifiMonthlySummary    = v.findViewById(R.id.tv_wifi_monthly_summary)
        panelWifiMonthly        = v.findViewById(R.id.panel_wifi_monthly)
        tvWifiMonthlyLimit      = v.findViewById(R.id.tv_wifi_monthly_limit_value)
        chipsWifiMonthlyLimit   = v.findViewById(R.id.chips_wifi_monthly_limit)
        seekWifiMonthlyLimit    = v.findViewById(R.id.seekbar_wifi_monthly_limit)
        customWifiMonthlyLimit  = v.findViewById(R.id.custom_wifi_monthly_limit)
        etWifiMonthlyLimit      = v.findViewById(R.id.et_wifi_monthly_limit)
        tvWifiMonthlyThrottle   = v.findViewById(R.id.tv_wifi_monthly_throttle)
        chipsWifiMonthlyThrottle= v.findViewById(R.id.chips_wifi_monthly_throttle)
        seekWifiMonthlyThrottle = v.findViewById(R.id.seekbar_wifi_monthly_throttle)
        customWifiMonthlyThrottle = v.findViewById(R.id.custom_wifi_monthly_throttle)
        etWifiMonthlyThrottle   = v.findViewById(R.id.et_wifi_monthly_throttle)

        switchWifiDaily         = v.findViewById(R.id.switch_wifi_daily)
        tvWifiDailySummary      = v.findViewById(R.id.tv_wifi_daily_summary)
        panelWifiDaily          = v.findViewById(R.id.panel_wifi_daily)
        tvWifiDailyLimit        = v.findViewById(R.id.tv_wifi_daily_limit_value)
        chipsWifiDailyLimit     = v.findViewById(R.id.chips_wifi_daily_limit)
        seekWifiDailyLimit      = v.findViewById(R.id.seekbar_wifi_daily_limit)
        customWifiDailyLimit    = v.findViewById(R.id.custom_wifi_daily_limit)
        etWifiDailyLimit        = v.findViewById(R.id.et_wifi_daily_limit)
        tvWifiDailyThrottle     = v.findViewById(R.id.tv_wifi_daily_throttle)
        chipsWifiDailyThrottle  = v.findViewById(R.id.chips_wifi_daily_throttle)
        seekWifiDailyThrottle   = v.findViewById(R.id.seekbar_wifi_daily_throttle)
        customWifiDailyThrottle = v.findViewById(R.id.custom_wifi_daily_throttle)
        etWifiDailyThrottle     = v.findViewById(R.id.et_wifi_daily_throttle)

        switchSimMonthly        = v.findViewById(R.id.switch_sim_monthly)
        tvSimMonthlySummary     = v.findViewById(R.id.tv_sim_monthly_summary)
        panelSimMonthly         = v.findViewById(R.id.panel_sim_monthly)
        tvSimMonthlyLimit       = v.findViewById(R.id.tv_sim_monthly_limit_value)
        chipsSimMonthlyLimit    = v.findViewById(R.id.chips_sim_monthly_limit)
        seekSimMonthlyLimit     = v.findViewById(R.id.seekbar_sim_monthly_limit)
        customSimMonthlyLimit   = v.findViewById(R.id.custom_sim_monthly_limit)
        etSimMonthlyLimit       = v.findViewById(R.id.et_sim_monthly_limit)
        tvSimMonthlyThrottle    = v.findViewById(R.id.tv_sim_monthly_throttle)
        chipsSimMonthlyThrottle = v.findViewById(R.id.chips_sim_monthly_throttle)
        seekSimMonthlyThrottle  = v.findViewById(R.id.seekbar_sim_monthly_throttle)
        customSimMonthlyThrottle= v.findViewById(R.id.custom_sim_monthly_throttle)
        etSimMonthlyThrottle    = v.findViewById(R.id.et_sim_monthly_throttle)

        switchSimDaily          = v.findViewById(R.id.switch_sim_daily)
        tvSimDailySummary       = v.findViewById(R.id.tv_sim_daily_summary)
        panelSimDaily           = v.findViewById(R.id.panel_sim_daily)
        tvSimDailyLimit         = v.findViewById(R.id.tv_sim_daily_limit_value)
        chipsSimDailyLimit      = v.findViewById(R.id.chips_sim_daily_limit)
        seekSimDailyLimit       = v.findViewById(R.id.seekbar_sim_daily_limit)
        customSimDailyLimit     = v.findViewById(R.id.custom_sim_daily_limit)
        etSimDailyLimit         = v.findViewById(R.id.et_sim_daily_limit)
        tvSimDailyThrottle      = v.findViewById(R.id.tv_sim_daily_throttle)
        chipsSimDailyThrottle   = v.findViewById(R.id.chips_sim_daily_throttle)
        seekSimDailyThrottle    = v.findViewById(R.id.seekbar_sim_daily_throttle)
        customSimDailyThrottle  = v.findViewById(R.id.custom_sim_daily_throttle)
        etSimDailyThrottle      = v.findViewById(R.id.et_sim_daily_throttle)
    }

    // ─── チップ構築 ───────────────────────────────────────────
    private fun buildAllChips() {
        val wifiColor = requireContext().getColor(R.color.wifi_color)
        val simColor  = requireContext().getColor(R.color.sim_color)

        buildMonthlyLimitChips(chipsWifiMonthlyLimit, wifiColor) { gb ->
            wifiMonthlyLimitGb = gb
            syncMonthlyLimitUi(seekWifiMonthlyLimit, tvWifiMonthlyLimit,
                chipsWifiMonthlyLimit, customWifiMonthlyLimit, etWifiMonthlyLimit, gb, wifiColor)
            updateWifiMonthlySummary()
        }
        buildThrottleChips(chipsWifiMonthlyThrottle, wifiColor) { kbps ->
            wifiMonthlyThrottleKbps = kbps
            syncThrottleUi(seekWifiMonthlyThrottle, tvWifiMonthlyThrottle,
                chipsWifiMonthlyThrottle, customWifiMonthlyThrottle, etWifiMonthlyThrottle, kbps, wifiColor)
            updateWifiMonthlySummary()
        }
        buildDailyLimitChips(chipsWifiDailyLimit, wifiColor) { mb ->
            wifiDailyLimitMb = mb
            syncDailyLimitUi(seekWifiDailyLimit, tvWifiDailyLimit,
                chipsWifiDailyLimit, customWifiDailyLimit, etWifiDailyLimit, mb, wifiColor)
            updateWifiDailySummary()
        }
        buildThrottleChips(chipsWifiDailyThrottle, wifiColor) { kbps ->
            wifiDailyThrottleKbps = kbps
            syncThrottleUi(seekWifiDailyThrottle, tvWifiDailyThrottle,
                chipsWifiDailyThrottle, customWifiDailyThrottle, etWifiDailyThrottle, kbps, wifiColor)
            updateWifiDailySummary()
        }

        buildMonthlyLimitChips(chipsSimMonthlyLimit, simColor) { gb ->
            simMonthlyLimitGb = gb
            syncMonthlyLimitUi(seekSimMonthlyLimit, tvSimMonthlyLimit,
                chipsSimMonthlyLimit, customSimMonthlyLimit, etSimMonthlyLimit, gb, simColor)
            updateSimMonthlySummary()
        }
        buildThrottleChips(chipsSimMonthlyThrottle, simColor) { kbps ->
            simMonthlyThrottleKbps = kbps
            syncThrottleUi(seekSimMonthlyThrottle, tvSimMonthlyThrottle,
                chipsSimMonthlyThrottle, customSimMonthlyThrottle, etSimMonthlyThrottle, kbps, simColor)
            updateSimMonthlySummary()
        }
        buildDailyLimitChips(chipsSimDailyLimit, simColor) { mb ->
            simDailyLimitMb = mb
            syncDailyLimitUi(seekSimDailyLimit, tvSimDailyLimit,
                chipsSimDailyLimit, customSimDailyLimit, etSimDailyLimit, mb, simColor)
            updateSimDailySummary()
        }
        buildThrottleChips(chipsSimDailyThrottle, simColor) { kbps ->
            simDailyThrottleKbps = kbps
            syncThrottleUi(seekSimDailyThrottle, tvSimDailyThrottle,
                chipsSimDailyThrottle, customSimDailyThrottle, etSimDailyThrottle, kbps, simColor)
            updateSimDailySummary()
        }
    }

    /** 月間上限チップ (GB プリセット + カスタム) */
    private fun buildMonthlyLimitChips(
        container: LinearLayout,
        accentColor: Int,
        onSelect: (Int) -> Unit
    ) {
        container.removeAllViews()
        monthlyLimitPresets.forEach { gb ->
            container.addView(makeChip("$gb GB", accentColor) { onSelect(gb) })
        }
        container.addView(makeChip("カスタム", accentColor) { onSelect(-1) })
    }

    /** 1日上限チップ (MB/GB プリセット + カスタム) */
    private fun buildDailyLimitChips(
        container: LinearLayout,
        accentColor: Int,
        onSelect: (Int) -> Unit
    ) {
        container.removeAllViews()
        dailyLimitPresets.forEach { mb ->
            container.addView(makeChip(formatMb(mb), accentColor) { onSelect(mb) })
        }
        container.addView(makeChip("カスタム", accentColor) { onSelect(-1) })
    }

    /** 速度チップ (kbps/Mbps プリセット + カスタム) */
    private fun buildThrottleChips(
        container: LinearLayout,
        accentColor: Int,
        onSelect: (Int) -> Unit
    ) {
        container.removeAllViews()
        throttlePresets.forEach { kbps ->
            container.addView(makeChip(formatKbps(kbps), accentColor) { onSelect(kbps) })
        }
        container.addView(makeChip("カスタム", accentColor) { onSelect(-1) })
    }

    /** チップ TextView を生成 */
    private fun makeChip(label: String, @Suppress("UNUSED_PARAMETER") accentColor: Int, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            text = label
            textSize = 12f
            setTextColor(requireContext().getColor(R.color.on_surface))
            background = requireContext().getDrawable(R.drawable.bg_chip_unselected)
            val hPad = (10 * dp).toInt()
            val vPad = (5 * dp).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * dp).toInt()
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ─── UI 同期ヘルパー ──────────────────────────────────────

    /** 月間上限: 値 → SeekBar + ラベル + チップ選択状態 + カスタム表示 */
    private fun syncMonthlyLimitUi(
        seek: SeekBar, tv: TextView,
        chips: LinearLayout, customPanel: LinearLayout, et: EditText,
        gb: Int, accentColor: Int
    ) {
        if (gb == -1) {
            // カスタム選択
            selectChip(chips, chips.childCount - 1, accentColor)
            customPanel.visibility = View.VISIBLE
            tv.text = et.text.toString().let {
                val v = it.toFloatOrNull()
                if (v != null && v > 0) "%.0f GB".format(v) else "-- GB"
            }
            return
        }
        customPanel.visibility = View.GONE
        val idx = monthlyLimitSteps.indexOfFirst { it >= gb }.takeIf { it >= 0 }
            ?: (monthlyLimitSteps.size - 1)
        seek.max = monthlyLimitSteps.size - 1
        seek.progress = idx
        tv.text = "$gb GB"
        tv.setTextColor(accentColor)
        val chipIdx = monthlyLimitPresets.indexOf(gb)
        selectChip(chips, chipIdx, accentColor) // -1 → 選択なし
    }

    /** 1日上限: 値 → SeekBar + ラベル + チップ選択状態 + カスタム表示 */
    private fun syncDailyLimitUi(
        seek: SeekBar, tv: TextView,
        chips: LinearLayout, customPanel: LinearLayout, et: EditText,
        mb: Int, accentColor: Int
    ) {
        if (mb == -1) {
            selectChip(chips, chips.childCount - 1, accentColor)
            customPanel.visibility = View.VISIBLE
            tv.text = et.text.toString().let {
                val v = it.toFloatOrNull()
                if (v != null && v > 0) formatMb((v * 1000).toInt()) else "--"
            }
            return
        }
        customPanel.visibility = View.GONE
        val idx = dailyLimitSteps.indexOfFirst { it >= mb }.takeIf { it >= 0 }
            ?: (dailyLimitSteps.size - 1)
        seek.max = dailyLimitSteps.size - 1
        seek.progress = idx
        tv.text = formatMb(mb)
        tv.setTextColor(accentColor)
        val chipIdx = dailyLimitPresets.indexOf(mb)
        selectChip(chips, chipIdx, accentColor)
    }

    /** 速度: 値 → SeekBar + ラベル + チップ選択状態 + カスタム表示 */
    private fun syncThrottleUi(
        seek: SeekBar, tv: TextView,
        chips: LinearLayout, customPanel: LinearLayout, et: EditText,
        kbps: Int, accentColor: Int
    ) {
        if (kbps == -1) {
            selectChip(chips, chips.childCount - 1, accentColor)
            customPanel.visibility = View.VISIBLE
            tv.text = et.text.toString().let {
                val v = it.toFloatOrNull()
                if (v != null && v > 0) formatKbps(v.toInt()) else "--"
            }
            return
        }
        customPanel.visibility = View.GONE
        val idx = throttleSteps.indexOfFirst { it >= kbps }.takeIf { it >= 0 }
            ?: (throttleSteps.size - 1)
        seek.max = throttleSteps.size - 1
        seek.progress = idx
        tv.text = formatKbps(kbps)
        tv.setTextColor(accentColor)
        val chipIdx = throttlePresets.indexOf(kbps)
        selectChip(chips, chipIdx, accentColor)
    }

    /** chips の index 番目を選択状態に、それ以外を非選択に */
    private fun selectChip(chips: LinearLayout, selectedIdx: Int, accentColor: Int) {
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as? TextView ?: continue
            if (i == selectedIdx) {
                chip.background = requireContext().getDrawable(R.drawable.bg_chip_selected)
                chip.setTextColor(accentColor)
            } else {
                chip.background = requireContext().getDrawable(R.drawable.bg_chip_unselected)
                chip.setTextColor(requireContext().getColor(R.color.on_surface))
            }
        }
    }

    // ─── applyUi ─────────────────────────────────────────────
    private fun applyUi() {
        val wifiColor = requireContext().getColor(R.color.wifi_color)
        val simColor  = requireContext().getColor(R.color.sim_color)

        switchWifiMonthly.isChecked = wifiMonthlyEnabled
        panelWifiMonthly.visibility = if (wifiMonthlyEnabled) View.VISIBLE else View.GONE
        syncMonthlyLimitUi(seekWifiMonthlyLimit, tvWifiMonthlyLimit,
            chipsWifiMonthlyLimit, customWifiMonthlyLimit, etWifiMonthlyLimit, wifiMonthlyLimitGb, wifiColor)
        syncThrottleUi(seekWifiMonthlyThrottle, tvWifiMonthlyThrottle,
            chipsWifiMonthlyThrottle, customWifiMonthlyThrottle, etWifiMonthlyThrottle, wifiMonthlyThrottleKbps, wifiColor)
        updateWifiMonthlySummary()

        switchWifiDaily.isChecked = wifiDailyEnabled
        panelWifiDaily.visibility = if (wifiDailyEnabled) View.VISIBLE else View.GONE
        syncDailyLimitUi(seekWifiDailyLimit, tvWifiDailyLimit,
            chipsWifiDailyLimit, customWifiDailyLimit, etWifiDailyLimit, wifiDailyLimitMb, wifiColor)
        syncThrottleUi(seekWifiDailyThrottle, tvWifiDailyThrottle,
            chipsWifiDailyThrottle, customWifiDailyThrottle, etWifiDailyThrottle, wifiDailyThrottleKbps, wifiColor)
        updateWifiDailySummary()

        switchSimMonthly.isChecked = simMonthlyEnabled
        panelSimMonthly.visibility = if (simMonthlyEnabled) View.VISIBLE else View.GONE
        syncMonthlyLimitUi(seekSimMonthlyLimit, tvSimMonthlyLimit,
            chipsSimMonthlyLimit, customSimMonthlyLimit, etSimMonthlyLimit, simMonthlyLimitGb, simColor)
        syncThrottleUi(seekSimMonthlyThrottle, tvSimMonthlyThrottle,
            chipsSimMonthlyThrottle, customSimMonthlyThrottle, etSimMonthlyThrottle, simMonthlyThrottleKbps, simColor)
        updateSimMonthlySummary()

        switchSimDaily.isChecked = simDailyEnabled
        panelSimDaily.visibility = if (simDailyEnabled) View.VISIBLE else View.GONE
        syncDailyLimitUi(seekSimDailyLimit, tvSimDailyLimit,
            chipsSimDailyLimit, customSimDailyLimit, etSimDailyLimit, simDailyLimitMb, simColor)
        syncThrottleUi(seekSimDailyThrottle, tvSimDailyThrottle,
            chipsSimDailyThrottle, customSimDailyThrottle, etSimDailyThrottle, simDailyThrottleKbps, simColor)
        updateSimDailySummary()
    }

    // ─── SeekBar + EditText リスナー設定 ─────────────────────
    private fun setupListeners() {
        val wifiColor = requireContext().getColor(R.color.wifi_color)
        val simColor  = requireContext().getColor(R.color.sim_color)

        switchWifiMonthly.setOnCheckedChangeListener { _, c ->
            wifiMonthlyEnabled = c
            panelWifiMonthly.visibility = if (c) View.VISIBLE else View.GONE
            updateWifiMonthlySummary()
        }
        switchWifiDaily.setOnCheckedChangeListener { _, c ->
            wifiDailyEnabled = c
            panelWifiDaily.visibility = if (c) View.VISIBLE else View.GONE
            updateWifiDailySummary()
        }
        switchSimMonthly.setOnCheckedChangeListener { _, c ->
            simMonthlyEnabled = c
            panelSimMonthly.visibility = if (c) View.VISIBLE else View.GONE
            updateSimMonthlySummary()
        }
        switchSimDaily.setOnCheckedChangeListener { _, c ->
            simDailyEnabled = c
            panelSimDaily.visibility = if (c) View.VISIBLE else View.GONE
            updateSimDailySummary()
        }

        // --- WiFi 月間 ---
        seekWifiMonthlyLimit.setOnSeekBarChangeListener(monthlyLimitSeekBar { gb ->
            wifiMonthlyLimitGb = gb
            syncMonthlyLimitUi(seekWifiMonthlyLimit, tvWifiMonthlyLimit,
                chipsWifiMonthlyLimit, customWifiMonthlyLimit, etWifiMonthlyLimit, gb, wifiColor)
            updateWifiMonthlySummary()
        })
        seekWifiMonthlyThrottle.setOnSeekBarChangeListener(throttleSeekBar { kbps ->
            wifiMonthlyThrottleKbps = kbps
            syncThrottleUi(seekWifiMonthlyThrottle, tvWifiMonthlyThrottle,
                chipsWifiMonthlyThrottle, customWifiMonthlyThrottle, etWifiMonthlyThrottle, kbps, wifiColor)
            updateWifiMonthlySummary()
        })
        etWifiMonthlyLimit.addTextChangedListener(customGbWatcher { gb ->
            wifiMonthlyLimitGb = gb
            tvWifiMonthlyLimit.text = if (gb > 0) "$gb GB" else "-- GB"
            updateWifiMonthlySummary()
        })
        etWifiMonthlyThrottle.addTextChangedListener(customKbpsWatcher { kbps ->
            wifiMonthlyThrottleKbps = kbps
            tvWifiMonthlyThrottle.text = if (kbps > 0) formatKbps(kbps) else "--"
            updateWifiMonthlySummary()
        })

        // --- WiFi 1日 ---
        seekWifiDailyLimit.setOnSeekBarChangeListener(dailyLimitSeekBar { mb ->
            wifiDailyLimitMb = mb
            syncDailyLimitUi(seekWifiDailyLimit, tvWifiDailyLimit,
                chipsWifiDailyLimit, customWifiDailyLimit, etWifiDailyLimit, mb, wifiColor)
            updateWifiDailySummary()
        })
        seekWifiDailyThrottle.setOnSeekBarChangeListener(throttleSeekBar { kbps ->
            wifiDailyThrottleKbps = kbps
            syncThrottleUi(seekWifiDailyThrottle, tvWifiDailyThrottle,
                chipsWifiDailyThrottle, customWifiDailyThrottle, etWifiDailyThrottle, kbps, wifiColor)
            updateWifiDailySummary()
        })
        etWifiDailyLimit.addTextChangedListener(customMbWatcher { mb ->
            wifiDailyLimitMb = mb
            tvWifiDailyLimit.text = if (mb > 0) formatMb(mb) else "--"
            updateWifiDailySummary()
        })
        etWifiDailyThrottle.addTextChangedListener(customKbpsWatcher { kbps ->
            wifiDailyThrottleKbps = kbps
            tvWifiDailyThrottle.text = if (kbps > 0) formatKbps(kbps) else "--"
            updateWifiDailySummary()
        })

        // --- SIM 月間 ---
        seekSimMonthlyLimit.setOnSeekBarChangeListener(monthlyLimitSeekBar { gb ->
            simMonthlyLimitGb = gb
            syncMonthlyLimitUi(seekSimMonthlyLimit, tvSimMonthlyLimit,
                chipsSimMonthlyLimit, customSimMonthlyLimit, etSimMonthlyLimit, gb, simColor)
            updateSimMonthlySummary()
        })
        seekSimMonthlyThrottle.setOnSeekBarChangeListener(throttleSeekBar { kbps ->
            simMonthlyThrottleKbps = kbps
            syncThrottleUi(seekSimMonthlyThrottle, tvSimMonthlyThrottle,
                chipsSimMonthlyThrottle, customSimMonthlyThrottle, etSimMonthlyThrottle, kbps, simColor)
            updateSimMonthlySummary()
        })
        etSimMonthlyLimit.addTextChangedListener(customGbWatcher { gb ->
            simMonthlyLimitGb = gb
            tvSimMonthlyLimit.text = if (gb > 0) "$gb GB" else "-- GB"
            updateSimMonthlySummary()
        })
        etSimMonthlyThrottle.addTextChangedListener(customKbpsWatcher { kbps ->
            simMonthlyThrottleKbps = kbps
            tvSimMonthlyThrottle.text = if (kbps > 0) formatKbps(kbps) else "--"
            updateSimMonthlySummary()
        })

        // --- SIM 1日 ---
        seekSimDailyLimit.setOnSeekBarChangeListener(dailyLimitSeekBar { mb ->
            simDailyLimitMb = mb
            syncDailyLimitUi(seekSimDailyLimit, tvSimDailyLimit,
                chipsSimDailyLimit, customSimDailyLimit, etSimDailyLimit, mb, simColor)
            updateSimDailySummary()
        })
        seekSimDailyThrottle.setOnSeekBarChangeListener(throttleSeekBar { kbps ->
            simDailyThrottleKbps = kbps
            syncThrottleUi(seekSimDailyThrottle, tvSimDailyThrottle,
                chipsSimDailyThrottle, customSimDailyThrottle, etSimDailyThrottle, kbps, simColor)
            updateSimDailySummary()
        })
        etSimDailyLimit.addTextChangedListener(customMbWatcher { mb ->
            simDailyLimitMb = mb
            tvSimDailyLimit.text = if (mb > 0) formatMb(mb) else "--"
            updateSimDailySummary()
        })
        etSimDailyThrottle.addTextChangedListener(customKbpsWatcher { kbps ->
            simDailyThrottleKbps = kbps
            tvSimDailyThrottle.text = if (kbps > 0) formatKbps(kbps) else "--"
            updateSimDailySummary()
        })
    }

    // ─── SeekBar ファクトリ ────────────────────────────────────
    private fun monthlyLimitSeekBar(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
            if (!fromUser) return
            onValue(monthlyLimitSteps[p.coerceIn(0, monthlyLimitSteps.size - 1)])
        }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun dailyLimitSeekBar(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
            if (!fromUser) return
            onValue(dailyLimitSteps[p.coerceIn(0, dailyLimitSteps.size - 1)])
        }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun throttleSeekBar(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
            if (!fromUser) return
            onValue(throttleSteps[p.coerceIn(0, throttleSteps.size - 1)])
        }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    // ─── カスタム入力 TextWatcher ─────────────────────────────
    /** GB入力: "30" → 30 (GB整数) */
    private fun customGbWatcher(onValue: (Int) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val gb = s.toString().trim().toFloatOrNull()?.toInt() ?: 0
            onValue(gb)
        }
    }

    /** MB入力: "1.5" GB → 1500 MB, "500" → 500 MB (1未満は MB扱い) */
    private fun customMbWatcher(onValue: (Int) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val v = s.toString().trim().toFloatOrNull() ?: 0f
            // 入力が 0..100 の場合 GB として解釈、それ以上は MB として解釈
            val mb = if (v in 0.01f..100f && s.toString().contains('.').not()) {
                (v * 1000).toInt()
            } else {
                v.toInt()
            }
            onValue(mb)
        }
    }

    /** kbps/Mbps 入力: 1000未満 → kbps, 以上 → kbps そのまま。
     *  ユーザーは Mbps 単位で入力: "1" → 1000kbps, "0.5" → 500kbps */
    private fun customKbpsWatcher(onValue: (Int) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val v = s.toString().trim().toFloatOrNull() ?: 0f
            // ユーザーは Mbps で入力する想定 → kbps に変換
            val kbps = (v * 1000).toInt()
            onValue(kbps)
        }
    }

    // ─── サマリー更新 ─────────────────────────────────────────
    private fun updateWifiMonthlySummary() {
        tvWifiMonthlySummary.text =
            if (wifiMonthlyEnabled) "$wifiMonthlyLimitGb GB 超過後 ${formatKbps(wifiMonthlyThrottleKbps)} に制限"
            else "無効"
    }

    private fun updateWifiDailySummary() {
        tvWifiDailySummary.text =
            if (wifiDailyEnabled) "${formatMb(wifiDailyLimitMb)} 超過後 ${formatKbps(wifiDailyThrottleKbps)} に制限"
            else "無効"
    }

    private fun updateSimMonthlySummary() {
        tvSimMonthlySummary.text =
            if (simMonthlyEnabled) "$simMonthlyLimitGb GB 超過後 ${formatKbps(simMonthlyThrottleKbps)} に制限"
            else "無効"
    }

    private fun updateSimDailySummary() {
        tvSimDailySummary.text =
            if (simDailyEnabled) "${formatMb(simDailyLimitMb)} 超過後 ${formatKbps(simDailyThrottleKbps)} に制限"
            else "無効"
    }

    // ─── 設定の保存/読み込み ──────────────────────────────────
    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_BANDWIDTH, Context.MODE_PRIVATE)

        wifiMonthlyEnabled      = prefs.getBoolean(KEY_WIFI_MONTHLY_ENABLED, false)
        wifiMonthlyLimitGb      = prefs.getInt(KEY_WIFI_MONTHLY_LIMIT_GB, BW_DEFAULT_MONTHLY_LIMIT_GB).coerceIn(1, 200)
        // 旧データは Mbps 単位で保存されている可能性があるので変換
        val rawWifiMT = prefs.getInt(KEY_WIFI_MONTHLY_THROTTLE, BW_DEFAULT_THROTTLE_MBPS)
        wifiMonthlyThrottleKbps = if (rawWifiMT <= 100) rawWifiMT * 1000 else rawWifiMT

        wifiDailyEnabled        = prefs.getBoolean(KEY_WIFI_DAILY_ENABLED, false)
        wifiDailyLimitMb        = prefs.getInt(KEY_WIFI_DAILY_LIMIT_MB, BW_DEFAULT_DAILY_LIMIT_MB).coerceIn(100, 10000)
        val rawWifiDT = prefs.getInt(KEY_WIFI_DAILY_THROTTLE, BW_DEFAULT_THROTTLE_MBPS)
        wifiDailyThrottleKbps   = if (rawWifiDT <= 100) rawWifiDT * 1000 else rawWifiDT

        simMonthlyEnabled       = prefs.getBoolean(KEY_SIM_MONTHLY_ENABLED, false)
        simMonthlyLimitGb       = prefs.getInt(KEY_SIM_MONTHLY_LIMIT_GB, BW_DEFAULT_MONTHLY_LIMIT_GB).coerceIn(1, 200)
        val rawSimMT = prefs.getInt(KEY_SIM_MONTHLY_THROTTLE, BW_DEFAULT_THROTTLE_MBPS)
        simMonthlyThrottleKbps  = if (rawSimMT <= 100) rawSimMT * 1000 else rawSimMT

        simDailyEnabled         = prefs.getBoolean(KEY_SIM_DAILY_ENABLED, false)
        simDailyLimitMb         = prefs.getInt(KEY_SIM_DAILY_LIMIT_MB, BW_DEFAULT_DAILY_LIMIT_MB).coerceIn(100, 10000)
        val rawSimDT = prefs.getInt(KEY_SIM_DAILY_THROTTLE, BW_DEFAULT_THROTTLE_MBPS)
        simDailyThrottleKbps    = if (rawSimDT <= 100) rawSimDT * 1000 else rawSimDT
    }

    private fun saveSettings() {
        requireContext().getSharedPreferences(PREFS_BANDWIDTH, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIFI_MONTHLY_ENABLED,  wifiMonthlyEnabled)
            .putInt(KEY_WIFI_MONTHLY_LIMIT_GB,     wifiMonthlyLimitGb)
            .putInt(KEY_WIFI_MONTHLY_THROTTLE,     wifiMonthlyThrottleKbps)
            .putBoolean(KEY_WIFI_DAILY_ENABLED,    wifiDailyEnabled)
            .putInt(KEY_WIFI_DAILY_LIMIT_MB,       wifiDailyLimitMb)
            .putInt(KEY_WIFI_DAILY_THROTTLE,       wifiDailyThrottleKbps)
            .putBoolean(KEY_SIM_MONTHLY_ENABLED,   simMonthlyEnabled)
            .putInt(KEY_SIM_MONTHLY_LIMIT_GB,      simMonthlyLimitGb)
            .putInt(KEY_SIM_MONTHLY_THROTTLE,      simMonthlyThrottleKbps)
            .putBoolean(KEY_SIM_DAILY_ENABLED,     simDailyEnabled)
            .putInt(KEY_SIM_DAILY_LIMIT_MB,        simDailyLimitMb)
            .putInt(KEY_SIM_DAILY_THROTTLE,        simDailyThrottleKbps)
            .apply()

        Toast.makeText(requireContext(), "保存しました", Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }

    // ─── フォーマット ─────────────────────────────────────────
    private fun formatMb(mb: Int): String =
        if (mb < 1000) "$mb MB" else "%.1f GB".format(mb / 1000f)

    private fun formatKbps(kbps: Int): String = when {
        kbps < 1000 -> "$kbps kbps"
        kbps % 1000 == 0 -> "${kbps / 1000} Mbps"
        else -> "%.1f Mbps".format(kbps / 1000f)
    }
}
