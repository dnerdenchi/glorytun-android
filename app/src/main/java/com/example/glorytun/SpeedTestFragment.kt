package com.example.glorytun

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class SpeedTestFragment : Fragment() {
    private val viewModel: VpnViewModel by activityViewModels()

    private lateinit var tester: NetworkSpeedTester
    private lateinit var statusText: TextView
    private lateinit var runAllButton: MaterialButton
    private lateinit var runWifiButton: MaterialButton
    private lateinit var runSimButton: MaterialButton
    private lateinit var runBondingButton: MaterialButton
    private lateinit var wifiViews: ResultViews
    private lateinit var simViews: ResultViews
    private lateinit var bondingViews: ResultViews
    private var activeJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_speed_test, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tester = NetworkSpeedTester(requireContext())
        statusText = view.findViewById(R.id.tv_speed_test_status)
        runAllButton = view.findViewById(R.id.btn_run_all_speed_tests)
        runWifiButton = view.findViewById(R.id.btn_run_wifi_speed_test)
        runSimButton = view.findViewById(R.id.btn_run_sim_speed_test)
        runBondingButton = view.findViewById(R.id.btn_run_bonding_speed_test)

        wifiViews = resultViews(view, R.id.wifi_result, "Wi-Fi")
        simViews = resultViews(view, R.id.sim_result, "SIM")
        bondingViews = resultViews(view, R.id.bonding_result, "ボンディング")

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        runAllButton.setOnClickListener {
            runTests(listOf(SpeedTestRoute.WIFI, SpeedTestRoute.CELLULAR, SpeedTestRoute.BONDING))
        }
        runWifiButton.setOnClickListener { runTests(listOf(SpeedTestRoute.WIFI)) }
        runSimButton.setOnClickListener { runTests(listOf(SpeedTestRoute.CELLULAR)) }
        runBondingButton.setOnClickListener { runTests(listOf(SpeedTestRoute.BONDING)) }

        viewModel.connectionState.observe(viewLifecycleOwner) {
            updateBondingHint()
        }
        updateBondingHint()
    }

    override fun onDestroyView() {
        activeJob?.cancel()
        super.onDestroyView()
    }

    private fun runTests(routes: List<SpeedTestRoute>) {
        activeJob?.cancel()
        activeJob = viewLifecycleOwner.lifecycleScope.launch {
            setControlsEnabled(false)
            statusText.text = "測定中です。各回線に約3MBの下り通信を流します。"

            routes.forEach { route ->
                val views = viewsFor(route)
                if (route == SpeedTestRoute.BONDING && viewModel.connectionState.value != ConnectionStates.VPN_CONNECTED) {
                    applyResult(
                        views,
                        SpeedTestResult(
                            route = route,
                            errorMessage = "VPN接続中に測定してください"
                        )
                    )
                    return@forEach
                }

                views.state.text = "測定中"
                views.detail.text = "${route.title} の回線で下り速度と遅延を測っています"
                views.speed.text = "--"
                views.latency.text = "--"

                val result = tester.measure(route)
                if (!isAdded) return@launch
                applyResult(views, result)
            }

            statusText.text = "測定が完了しました。"
            setControlsEnabled(true)
        }
    }

    private fun applyResult(views: ResultViews, result: SpeedTestResult) {
        if (result.succeeded) {
            views.state.text = "完了"
            views.speed.text = NetworkSpeedTester.formatSpeed(result.speedMbps)
            views.latency.text = NetworkSpeedTester.formatLatency(result.latencyMs)
            views.detail.text = String.format(
                Locale.US,
                "%.1f MB / %.1f 秒",
                result.downloadedBytes / 1_000_000.0,
                result.elapsedMs / 1000.0
            )
        } else {
            views.state.text = "未測定"
            views.speed.text = "--"
            views.latency.text = "--"
            views.detail.text = result.errorMessage ?: "測定に失敗しました"
        }
    }

    private fun updateBondingHint() {
        if (!::bondingViews.isInitialized) return
        if (viewModel.connectionState.value == ConnectionStates.VPN_CONNECTED) {
            bondingViews.detail.text = "VPN接続中の既定経路で測定します"
        } else if (bondingViews.state.text == "未測定") {
            bondingViews.detail.text = "ボンディングはVPN接続後に測定できます"
        }
    }

    private fun viewsFor(route: SpeedTestRoute): ResultViews = when (route) {
        SpeedTestRoute.WIFI -> wifiViews
        SpeedTestRoute.CELLULAR -> simViews
        SpeedTestRoute.BONDING -> bondingViews
    }

    private fun setControlsEnabled(enabled: Boolean) {
        runAllButton.isEnabled = enabled
        runWifiButton.isEnabled = enabled
        runSimButton.isEnabled = enabled
        runBondingButton.isEnabled = enabled
    }

    private fun resultViews(view: View, rootId: Int, title: String): ResultViews {
        val root = view.findViewById<View>(rootId)
        root.findViewById<TextView>(R.id.tv_result_title).text = title
        return ResultViews(
            state = root.findViewById(R.id.tv_result_state),
            speed = root.findViewById(R.id.tv_result_speed),
            latency = root.findViewById(R.id.tv_result_latency),
            detail = root.findViewById(R.id.tv_result_detail),
        )
    }

    private data class ResultViews(
        val state: TextView,
        val speed: TextView,
        val latency: TextView,
        val detail: TextView,
    )
}
