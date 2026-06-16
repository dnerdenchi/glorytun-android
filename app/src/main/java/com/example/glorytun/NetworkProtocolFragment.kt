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
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class NetworkProtocolFragment : Fragment() {

    companion object {
        const val PREFS_NAME = "network_mode_prefs"
        const val KEY_MODE = "network_mode"
        const val KEY_THRESHOLD_KBPS = "speed_threshold_kbps"

        const val MODE_BONDING    = "BONDING"
        const val MODE_WIFI_FIRST = "WIFI_FIRST"
        const val MODE_SIM_FIRST  = "SIM_FIRST"

        const val DEFAULT_THRESHOLD_KBPS = 5000 // 5 Mbps
    }

    // 速度制限 (kbps単位で統一保持: 128, 256, 512, 1000, 2000, 5000, 10000)
    private val throttleSteps = intArrayOf(128, 256, 512, 1000, 2000, 5000, 10000)
    private val throttlePresets = intArrayOf(256, 512, 1000, 5000) // チップ表示する値 (kbps)

    private lateinit var cardBonding:    LinearLayout
    private lateinit var cardWifiFirst:  LinearLayout
    private lateinit var cardSimFirst:   LinearLayout
    private lateinit var radioBonding:   RadioButton
    private lateinit var radioWifiFirst: RadioButton
    private lateinit var radioSimFirst:  RadioButton
    
    private lateinit var cardThreshold:  LinearLayout
    private lateinit var tvThresholdValue: TextView
    private lateinit var tvThresholdDesc:  TextView
    private lateinit var chipsThreshold: LinearLayout
    private lateinit var seekbarThreshold: SeekBar
    private lateinit var customThreshold: LinearLayout
    private lateinit var etThreshold: EditText
    
    private lateinit var btnSave: LinearLayout

    private var selectedMode   = MODE_BONDING
    private var thresholdKbps  = DEFAULT_THRESHOLD_KBPS

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_network_protocol, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cardBonding      = view.findViewById(R.id.card_bonding)
        cardWifiFirst    = view.findViewById(R.id.card_wifi_first)
        cardSimFirst     = view.findViewById(R.id.card_sim_first)
        radioBonding     = view.findViewById(R.id.radio_bonding)
        radioWifiFirst   = view.findViewById(R.id.radio_wifi_first)
        radioSimFirst    = view.findViewById(R.id.radio_sim_first)
        cardThreshold    = view.findViewById(R.id.card_threshold)
        tvThresholdValue = view.findViewById(R.id.tv_threshold_value)
        tvThresholdDesc  = view.findViewById(R.id.tv_threshold_desc)
        chipsThreshold   = view.findViewById(R.id.chips_threshold)
        seekbarThreshold = view.findViewById(R.id.seekbar_threshold)
        customThreshold  = view.findViewById(R.id.custom_threshold)
        etThreshold      = view.findViewById(R.id.et_threshold)
        btnSave          = view.findViewById(R.id.btn_save)

        loadSettings()
        buildThrottleChips()
        applySelectionUi()
        setupListeners()

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        btnSave.setOnClickListener { saveSettings() }
    }

    private fun setupListeners() {
        cardBonding.setOnClickListener   { selectMode(MODE_BONDING) }
        cardWifiFirst.setOnClickListener { selectMode(MODE_WIFI_FIRST) }
        cardSimFirst.setOnClickListener  { selectMode(MODE_SIM_FIRST) }

        seekbarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                thresholdKbps = throttleSteps[p.coerceIn(0, throttleSteps.size - 1)]
                syncThrottleUi()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        etThreshold.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().trim().toFloatOrNull() ?: 0f
                val kbps = (v * 1000).toInt()
                thresholdKbps = kbps
                tvThresholdValue.text = if (kbps > 0) formatKbps(kbps) else "--"
            }
        })
    }

    private fun buildThrottleChips() {
        chipsThreshold.removeAllViews()
        throttlePresets.forEach { kbps ->
            chipsThreshold.addView(makeChip(formatKbps(kbps)) {
                thresholdKbps = kbps
                syncThrottleUi()
            })
        }
        chipsThreshold.addView(makeChip("カスタム") {
            thresholdKbps = -1
            syncThrottleUi()
        })
    }

    private fun makeChip(label: String, onClick: () -> Unit): TextView {
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

    private fun selectMode(mode: String) {
        selectedMode = mode
        applySelectionUi()
    }

    private fun applySelectionUi() {
        val bgDefault  = R.drawable.bg_card
        val bgSelected = R.drawable.bg_card_selected

        cardBonding.setBackgroundResource(if (selectedMode == MODE_BONDING)    bgSelected else bgDefault)
        cardWifiFirst.setBackgroundResource(if (selectedMode == MODE_WIFI_FIRST) bgSelected else bgDefault)
        cardSimFirst.setBackgroundResource(if (selectedMode == MODE_SIM_FIRST)  bgSelected else bgDefault)

        radioBonding.isChecked   = selectedMode == MODE_BONDING
        radioWifiFirst.isChecked = selectedMode == MODE_WIFI_FIRST
        radioSimFirst.isChecked  = selectedMode == MODE_SIM_FIRST

        val showThreshold = selectedMode == MODE_WIFI_FIRST || selectedMode == MODE_SIM_FIRST
        cardThreshold.visibility = if (showThreshold) View.VISIBLE else View.GONE

        if (showThreshold) {
            tvThresholdDesc.text = if (selectedMode == MODE_WIFI_FIRST)
                "WiFi の推定帯域幅がこの値を下回ったとき SIM を追加します\n(SIM は不足分だけに制限されます)"
            else
                "SIM の推定帯域幅がこの値を下回ったとき WiFi を追加します\n(WiFi は不足分だけに制限されます)"
        }

        syncThrottleUi()
    }

    private fun syncThrottleUi() {
        val accentColor = requireContext().getColor(R.color.primary)
        if (thresholdKbps == -1) {
            selectChip(chipsThreshold, chipsThreshold.childCount - 1)
            customThreshold.visibility = View.VISIBLE
            tvThresholdValue.text = etThreshold.text.toString().let {
                val v = it.toFloatOrNull()
                if (v != null && v > 0) formatKbps((v * 1000).toInt()) else "--"
            }
            return
        }
        customThreshold.visibility = View.GONE
        val idx = throttleSteps.indexOfFirst { it >= thresholdKbps }.takeIf { it >= 0 }
            ?: (throttleSteps.size - 1)
        seekbarThreshold.max = throttleSteps.size - 1
        seekbarThreshold.progress = idx
        tvThresholdValue.text = formatKbps(thresholdKbps)
        tvThresholdValue.setTextColor(accentColor)
        val chipIdx = throttlePresets.indexOf(thresholdKbps)
        selectChip(chipsThreshold, chipIdx)
    }

    private fun selectChip(chips: LinearLayout, selectedIdx: Int) {
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as? TextView ?: continue
            if (i == selectedIdx) {
                chip.background = requireContext().getDrawable(R.drawable.bg_chip_selected)
                chip.setTextColor(requireContext().getColor(R.color.on_surface))
            } else {
                chip.background = requireContext().getDrawable(R.drawable.bg_chip_unselected)
                chip.setTextColor(requireContext().getColor(R.color.on_surface))
            }
        }
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedMode  = prefs.getString(KEY_MODE, MODE_BONDING) ?: MODE_BONDING
        
        // Migrate from old MBps format safely
        val oldMBps = prefs.getInt("speed_threshold_mbps", -1)
        if (oldMBps != -1 && !prefs.contains(KEY_THRESHOLD_KBPS)) {
            thresholdKbps = oldMBps * 1000
            // Optionally clear the old key, but leaving it is fine too
        } else {
            thresholdKbps = prefs.getInt(KEY_THRESHOLD_KBPS, DEFAULT_THRESHOLD_KBPS)
        }
    }

    private fun saveSettings() {
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, selectedMode)
            .putInt(KEY_THRESHOLD_KBPS, thresholdKbps)
            .apply()

        val modeName = when (selectedMode) {
            MODE_BONDING    -> "ボンディング高速化"
            MODE_WIFI_FIRST -> "WiFi 優先 (しきい値 ${formatKbps(thresholdKbps)})"
            MODE_SIM_FIRST  -> "SIM 優先 (しきい値 ${formatKbps(thresholdKbps)})"
            else -> selectedMode
        }
        Toast.makeText(requireContext(), "保存しました: $modeName", Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }

    private fun formatKbps(kbps: Int): String = when {
        kbps < 1000 -> "$kbps kbps"
        kbps % 1000 == 0 -> "${kbps / 1000} Mbps"
        else -> "%.1f Mbps".format(kbps / 1000f)
    }
}
