package com.example.glorytun

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.children
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import kotlin.math.abs

class BandwidthFragment : Fragment() {

    private enum class LimitTab(
        val title: String,
        val description: String,
    ) {
        ALWAYS(
            "常時制限",
            "接続中は常に、Wi-Fi／SIMそれぞれの通信速度を設定値以下に抑えます。",
        ),
        MONTHLY(
            "月間制限",
            "今月の通信量が上限に達した回線だけ、月が変わるまで超過後速度へ切り替えます。",
        ),
        DAILY(
            "1日制限",
            "今日の通信量が上限に達した回線だけ、翌日まで超過後速度へ切り替えます。",
        ),
    }

    private enum class NetworkType(
        val title: String,
        val colorRes: Int,
    ) {
        WIFI("Wi-Fi", R.color.wifi_color),
        SIM("SIM", R.color.sim_color),
    }

    private data class NetworkState(
        var alwaysEnabled: Boolean = false,
        var alwaysRateKbps: Int = DEFAULT_RATE_KBPS,
        var monthlyEnabled: Boolean = false,
        var monthlyLimitGb: Int = GlorytunConstants.BW_DEFAULT_MONTHLY_LIMIT_GB,
        var monthlyRateKbps: Int = DEFAULT_RATE_KBPS,
        var dailyEnabled: Boolean = false,
        var dailyLimitMb: Int = GlorytunConstants.BW_DEFAULT_DAILY_LIMIT_MB,
        var dailyRateKbps: Int = DEFAULT_RATE_KBPS,
    )

    private lateinit var tabs: TabLayout
    private lateinit var description: TextView
    private lateinit var panels: LinearLayout
    private val states = mutableMapOf(
        NetworkType.WIFI to NetworkState(),
        NetworkType.SIM to NetworkState(),
    )
    private var selectedTab = LimitTab.ALWAYS

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_bandwidth, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tabs = view.findViewById(R.id.tabs_bandwidth)
        description = view.findViewById(R.id.tv_tab_description)
        panels = view.findViewById(R.id.network_panels)

        loadSettings()
        selectedTab = LimitTab.entries.getOrElse(
            savedInstanceState?.getInt(STATE_SELECTED_TAB) ?: 0,
        ) { LimitTab.ALWAYS }
        setupTabs()
        renderSelectedTab()

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btn_save).setOnClickListener {
            saveSettings()
            Toast.makeText(requireContext(), "帯域幅設定を保存しました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab.ordinal)
        super.onSaveInstanceState(outState)
    }

    private fun setupTabs() {
        LimitTab.entries.forEach { tab ->
            tabs.addTab(tabs.newTab().setText(tab.title), tab == selectedTab)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = LimitTab.entries[tab.position]
                renderSelectedTab()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun renderSelectedTab() {
        description.text = selectedTab.description
        panels.removeAllViews()
        NetworkType.entries.forEach { network ->
            panels.addView(
                when (selectedTab) {
                    LimitTab.ALWAYS -> createAlwaysPanel(network)
                    LimitTab.MONTHLY,
                    LimitTab.DAILY,
                    -> createQuotaPanel(network, selectedTab)
                },
            )
        }
    }

    private fun createAlwaysPanel(network: NetworkType): View {
        val root = layoutInflater.inflate(
            R.layout.item_bandwidth_always_network,
            panels,
            false,
        )
        val state = states.getValue(network)
        val accent = requireContext().getColor(network.colorRes)
        val title = root.findViewById<TextView>(R.id.tv_network_title)
        val enabledSwitch = root.findViewById<SwitchMaterial>(R.id.switch_enabled)
        val details = root.findViewById<ViewGroup>(R.id.panel_details)

        title.text = network.title
        title.setTextColor(accent)
        enabledSwitch.contentDescription = "${network.title}の常時制限を有効にする"
        enabledSwitch.isChecked = state.alwaysEnabled
        enabledSwitch.setOnCheckedChangeListener { _, enabled ->
            state.alwaysEnabled = enabled
            setPanelEnabled(details, enabled)
        }

        setupRateControl(
            root = root,
            currentKbps = state.alwaysRateKbps,
            accent = accent,
        ) { state.alwaysRateKbps = it }
        setPanelEnabled(details, state.alwaysEnabled)
        return root
    }

    private fun createQuotaPanel(network: NetworkType, tab: LimitTab): View {
        val root = layoutInflater.inflate(
            R.layout.item_bandwidth_quota_network,
            panels,
            false,
        )
        val state = states.getValue(network)
        val accent = requireContext().getColor(network.colorRes)
        val title = root.findViewById<TextView>(R.id.tv_network_title)
        val limitLabel = root.findViewById<TextView>(R.id.tv_limit_label)
        val enabledSwitch = root.findViewById<SwitchMaterial>(R.id.switch_enabled)
        val details = root.findViewById<ViewGroup>(R.id.panel_details)
        val monthly = tab == LimitTab.MONTHLY

        title.text = network.title
        title.setTextColor(accent)
        limitLabel.text = if (monthly) "月間データ上限" else "1日データ上限"
        enabledSwitch.contentDescription =
            "${network.title}の${if (monthly) "月間" else "1日"}制限を有効にする"
        enabledSwitch.isChecked = if (monthly) state.monthlyEnabled else state.dailyEnabled
        enabledSwitch.setOnCheckedChangeListener { _, enabled ->
            if (monthly) state.monthlyEnabled = enabled else state.dailyEnabled = enabled
            setPanelEnabled(details, enabled)
        }

        if (monthly) {
            setupLimitControl(
                root,
                MONTHLY_LIMIT_STEPS,
                MONTHLY_LIMIT_PRESETS,
                state.monthlyLimitGb,
                accent,
                ::formatMonthlyLimit,
            ) { state.monthlyLimitGb = it }
            setupRateControl(root, state.monthlyRateKbps, accent) {
                state.monthlyRateKbps = it
            }
        } else {
            setupLimitControl(
                root,
                DAILY_LIMIT_STEPS,
                DAILY_LIMIT_PRESETS,
                state.dailyLimitMb,
                accent,
                ::formatDailyLimit,
            ) { state.dailyLimitMb = it }
            setupRateControl(root, state.dailyRateKbps, accent) {
                state.dailyRateKbps = it
            }
        }
        setPanelEnabled(details, enabledSwitch.isChecked)
        return root
    }

    private fun setupRateControl(
        root: View,
        currentKbps: Int,
        accent: Int,
        onChanged: (Int) -> Unit,
    ) {
        root.findViewById<Slider>(R.id.slider_rate).contentDescription =
            "${root.findViewById<TextView>(R.id.tv_network_title).text}の通信速度"
        setupDiscreteControl(
            valueText = root.findViewById(R.id.tv_rate_value),
            chipGroup = root.findViewById(R.id.chips_rate),
            slider = root.findViewById(R.id.slider_rate),
            steps = RATE_STEPS,
            presets = RATE_PRESETS,
            current = currentKbps,
            accent = accent,
            formatter = ::formatRate,
            onChanged = onChanged,
        )
    }

    private fun setupLimitControl(
        root: View,
        steps: IntArray,
        presets: IntArray,
        current: Int,
        accent: Int,
        formatter: (Int) -> String,
        onChanged: (Int) -> Unit,
    ) {
        root.findViewById<Slider>(R.id.slider_limit).contentDescription =
            root.findViewById<TextView>(R.id.tv_limit_label).text
        setupDiscreteControl(
            valueText = root.findViewById(R.id.tv_limit_value),
            chipGroup = root.findViewById(R.id.chips_limit),
            slider = root.findViewById(R.id.slider_limit),
            steps = steps,
            presets = presets,
            current = current,
            accent = accent,
            formatter = formatter,
            onChanged = onChanged,
        )
    }

    private fun setupDiscreteControl(
        valueText: TextView,
        chipGroup: ChipGroup,
        slider: Slider,
        steps: IntArray,
        presets: IntArray,
        current: Int,
        accent: Int,
        formatter: (Int) -> String,
        onChanged: (Int) -> Unit,
    ) {
        var selected = closestValue(steps, current)
        valueText.text = formatter(selected)
        valueText.setTextColor(accent)

        slider.valueFrom = 0f
        slider.valueTo = (steps.size - 1).toFloat()
        slider.stepSize = 1f
        slider.value = steps.indexOf(selected).toFloat()
        slider.trackActiveTintList = ColorStateList.valueOf(accent)
        slider.thumbTintList = ColorStateList.valueOf(accent)

        presets.forEach { preset ->
            val chip = createPresetChip(formatter(preset), preset == selected, accent)
            chip.setOnClickListener {
                selected = preset
                valueText.text = formatter(selected)
                slider.value = steps.indexOf(selected).toFloat()
                updateCheckedChip(chipGroup, selected)
                onChanged(selected)
            }
            chip.tag = preset
            chipGroup.addView(chip)
        }

        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            selected = steps[value.toInt().coerceIn(steps.indices)]
            valueText.text = formatter(selected)
            updateCheckedChip(chipGroup, selected)
            onChanged(selected)
        }
    }

    private fun createPresetChip(label: String, checked: Boolean, accent: Int): Chip {
        val selectedText = if (accent == requireContext().getColor(R.color.sim_color)) {
            Color.BLACK
        } else {
            Color.WHITE
        }
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        )
        return Chip(requireContext()).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = false
            setEnsureMinTouchTargetSize(true)
            chipBackgroundColor = ColorStateList(
                states,
                intArrayOf(accent, requireContext().getColor(R.color.surface_container_high)),
            )
            setTextColor(
                ColorStateList(
                    states,
                    intArrayOf(selectedText, requireContext().getColor(R.color.on_surface)),
                ),
            )
        }
    }

    private fun updateCheckedChip(group: ChipGroup, value: Int) {
        group.children.filterIsInstance<Chip>().forEach { chip ->
            chip.isChecked = chip.tag == value
        }
    }

    private fun setPanelEnabled(panel: ViewGroup, enabled: Boolean) {
        panel.alpha = if (enabled) 1f else DISABLED_ALPHA
        setEnabledRecursively(panel, enabled)
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            view.children.forEach { setEnabledRecursively(it, enabled) }
        }
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences(
            GlorytunConstants.PREFS_BANDWIDTH,
            Context.MODE_PRIVATE,
        )
        states[NetworkType.WIFI] = readNetworkState(prefs, "wifi")
        states[NetworkType.SIM] = readNetworkState(prefs, "sim")
    }

    private fun readNetworkState(
        prefs: android.content.SharedPreferences,
        prefix: String,
    ): NetworkState = NetworkState(
        alwaysEnabled = prefs.getBoolean("${prefix}_always_enabled", false),
        alwaysRateKbps = closestValue(
            RATE_STEPS,
            prefs.getInt("${prefix}_always_throttle_kbps", DEFAULT_RATE_KBPS),
        ),
        monthlyEnabled = prefs.getBoolean("${prefix}_monthly_enabled", false),
        monthlyLimitGb = closestValue(
            MONTHLY_LIMIT_STEPS,
            prefs.getInt(
                "${prefix}_monthly_limit_gb",
                GlorytunConstants.BW_DEFAULT_MONTHLY_LIMIT_GB,
            ),
        ),
        monthlyRateKbps = closestValue(
            RATE_STEPS,
            readLegacyCompatibleRate(prefs, "${prefix}_monthly_throttle_mbps"),
        ),
        dailyEnabled = prefs.getBoolean("${prefix}_daily_enabled", false),
        dailyLimitMb = closestValue(
            DAILY_LIMIT_STEPS,
            prefs.getInt(
                "${prefix}_daily_limit_mb",
                GlorytunConstants.BW_DEFAULT_DAILY_LIMIT_MB,
            ),
        ),
        dailyRateKbps = closestValue(
            RATE_STEPS,
            readLegacyCompatibleRate(prefs, "${prefix}_daily_throttle_mbps"),
        ),
    )

    private fun saveSettings() {
        val wifi = states.getValue(NetworkType.WIFI)
        val sim = states.getValue(NetworkType.SIM)
        requireContext().getSharedPreferences(
            GlorytunConstants.PREFS_BANDWIDTH,
            Context.MODE_PRIVATE,
        ).edit()
            .putBoolean(GlorytunConstants.KEY_WIFI_ALWAYS_ENABLED, wifi.alwaysEnabled)
            .putInt(GlorytunConstants.KEY_WIFI_ALWAYS_THROTTLE, wifi.alwaysRateKbps)
            .putBoolean(GlorytunConstants.KEY_WIFI_MONTHLY_ENABLED, wifi.monthlyEnabled)
            .putInt(GlorytunConstants.KEY_WIFI_MONTHLY_LIMIT_GB, wifi.monthlyLimitGb)
            .putInt(GlorytunConstants.KEY_WIFI_MONTHLY_THROTTLE, wifi.monthlyRateKbps)
            .putBoolean(GlorytunConstants.KEY_WIFI_DAILY_ENABLED, wifi.dailyEnabled)
            .putInt(GlorytunConstants.KEY_WIFI_DAILY_LIMIT_MB, wifi.dailyLimitMb)
            .putInt(GlorytunConstants.KEY_WIFI_DAILY_THROTTLE, wifi.dailyRateKbps)
            .putBoolean(GlorytunConstants.KEY_SIM_ALWAYS_ENABLED, sim.alwaysEnabled)
            .putInt(GlorytunConstants.KEY_SIM_ALWAYS_THROTTLE, sim.alwaysRateKbps)
            .putBoolean(GlorytunConstants.KEY_SIM_MONTHLY_ENABLED, sim.monthlyEnabled)
            .putInt(GlorytunConstants.KEY_SIM_MONTHLY_LIMIT_GB, sim.monthlyLimitGb)
            .putInt(GlorytunConstants.KEY_SIM_MONTHLY_THROTTLE, sim.monthlyRateKbps)
            .putBoolean(GlorytunConstants.KEY_SIM_DAILY_ENABLED, sim.dailyEnabled)
            .putInt(GlorytunConstants.KEY_SIM_DAILY_LIMIT_MB, sim.dailyLimitMb)
            .putInt(GlorytunConstants.KEY_SIM_DAILY_THROTTLE, sim.dailyRateKbps)
            .apply()
    }

    private fun readLegacyCompatibleRate(
        prefs: android.content.SharedPreferences,
        key: String,
    ): Int {
        val raw = prefs.getInt(key, GlorytunConstants.BW_DEFAULT_THROTTLE_MBPS)
        return if (raw in 1..10) raw * 1_000 else raw
    }

    private fun closestValue(values: IntArray, target: Int): Int =
        values.minByOrNull { abs(it - target) } ?: values.first()

    private fun formatRate(kbps: Int): String = when {
        kbps >= 1_000 && kbps % 1_000 == 0 -> "${kbps / 1_000} Mbps"
        kbps >= 1_000 -> String.format("%.1f Mbps", kbps / 1_000.0)
        else -> "$kbps kbps"
    }

    private fun formatMonthlyLimit(gb: Int): String = "$gb GB"

    private fun formatDailyLimit(mb: Int): String =
        if (mb >= 1_000 && mb % 1_000 == 0) "${mb / 1_000} GB" else "$mb MB"

    companion object {
        private const val STATE_SELECTED_TAB = "selected_bandwidth_tab"
        private const val DEFAULT_RATE_KBPS = 1_000
        private const val DISABLED_ALPHA = 0.45f

        private val RATE_STEPS = intArrayOf(
            128, 256, 512, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000,
        )
        private val RATE_PRESETS = intArrayOf(512, 1_000, 5_000, 10_000, 50_000)
        private val MONTHLY_LIMIT_STEPS = intArrayOf(
            1, 2, 3, 5, 10, 15, 20, 30, 50, 100, 200,
        )
        private val MONTHLY_LIMIT_PRESETS = intArrayOf(5, 10, 20, 30, 100)
        private val DAILY_LIMIT_STEPS = intArrayOf(
            100, 200, 500, 1_000, 2_000, 3_000, 5_000, 10_000,
        )
        private val DAILY_LIMIT_PRESETS = intArrayOf(500, 1_000, 3_000, 5_000, 10_000)
    }
}
