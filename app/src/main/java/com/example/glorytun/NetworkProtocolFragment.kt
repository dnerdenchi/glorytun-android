package com.example.glorytun

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        const val KEY_THRESHOLD_MBPS = "speed_threshold_mbps"

        const val MODE_BONDING    = "BONDING"
        const val MODE_WIFI_FIRST = "WIFI_FIRST"
        const val MODE_SIM_FIRST  = "SIM_FIRST"

        // しきい値: 1〜100 Mbps、デフォルト 40 Mbps (≒ 5 MB/s × 8)
        const val DEFAULT_THRESHOLD_MBPS = 40
        private const val THRESHOLD_MIN  = 1   // Mbps
        private const val THRESHOLD_MAX  = 100 // Mbps
    }

    private lateinit var cardBonding:    LinearLayout
    private lateinit var cardWifiFirst:  LinearLayout
    private lateinit var cardSimFirst:   LinearLayout
    private lateinit var radioBonding:   RadioButton
    private lateinit var radioWifiFirst: RadioButton
    private lateinit var radioSimFirst:  RadioButton
    private lateinit var cardThreshold:  LinearLayout
    private lateinit var tvThresholdValue: TextView
    private lateinit var tvThresholdDesc:  TextView
    private lateinit var seekbarThreshold: SeekBar
    private lateinit var btnSave: LinearLayout

    private var selectedMode   = MODE_BONDING
    private var thresholdMBps  = DEFAULT_THRESHOLD_MBPS

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
        seekbarThreshold = view.findViewById(R.id.seekbar_threshold)
        btnSave          = view.findViewById(R.id.btn_save)

        // SeekBar の range は THRESHOLD_MIN〜THRESHOLD_MAX (1〜50 MB/s)
        seekbarThreshold.max = THRESHOLD_MAX - THRESHOLD_MIN  // = 49

        loadSettings()
        applySelectionUi()

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        cardBonding.setOnClickListener   { selectMode(MODE_BONDING) }
        cardWifiFirst.setOnClickListener { selectMode(MODE_WIFI_FIRST) }
        cardSimFirst.setOnClickListener  { selectMode(MODE_SIM_FIRST) }

        seekbarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                thresholdMBps = THRESHOLD_MIN + progress
                updateThresholdLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        btnSave.setOnClickListener { saveSettings() }
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

        updateThresholdLabel()
        seekbarThreshold.progress = (thresholdMBps - THRESHOLD_MIN).coerceIn(0, seekbarThreshold.max)
    }

    private fun updateThresholdLabel() {
        tvThresholdValue.text = "$thresholdMBps Mbps"
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedMode  = prefs.getString(KEY_MODE, MODE_BONDING) ?: MODE_BONDING
        thresholdMBps = prefs.getInt(KEY_THRESHOLD_MBPS, DEFAULT_THRESHOLD_MBPS)
            .coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
    }

    private fun saveSettings() {
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, selectedMode)
            .putInt(KEY_THRESHOLD_MBPS, thresholdMBps)
            .apply()

        val modeName = when (selectedMode) {
            MODE_BONDING    -> "ボンディング高速化"
            MODE_WIFI_FIRST -> "WiFi 優先 (しきい値 ${thresholdMBps} MB/s)"
            MODE_SIM_FIRST  -> "SIM 優先 (しきい値 ${thresholdMBps} MB/s)"
            else -> selectedMode
        }
        Toast.makeText(requireContext(), "保存しました: $modeName", Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }
}
