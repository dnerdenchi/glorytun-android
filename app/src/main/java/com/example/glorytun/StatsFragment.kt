package com.example.glorytun

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

class StatsFragment : Fragment() {

    private val viewModel: VpnViewModel by activityViewModels()

    private lateinit var tvTotalData: TextView
    private lateinit var tvWifiTotal: TextView
    private lateinit var tvSimTotal: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMaxThroughput: TextView
    private lateinit var statsGraph: TrafficGraphView

    // 時間フィルター
    private lateinit var filter1h: TextView
    private lateinit var filter1d: TextView
    private lateinit var filter1w: TextView
    private lateinit var filter1m: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTotalData = view.findViewById(R.id.tv_total_data)
        tvWifiTotal = view.findViewById(R.id.tv_wifi_total)
        tvSimTotal = view.findViewById(R.id.tv_sim_total)
        tvAvgSpeed = view.findViewById(R.id.tv_avg_speed)
        tvMaxThroughput = view.findViewById(R.id.tv_max_throughput)
        statsGraph = view.findViewById(R.id.stats_traffic_graph)

        filter1h = view.findViewById(R.id.filter_1h)
        filter1d = view.findViewById(R.id.filter_1d)
        filter1w = view.findViewById(R.id.filter_1w)
        filter1m = view.findViewById(R.id.filter_1m)

        setupFilterButtons()
        observeViewModel()
    }

    private fun setupFilterButtons() {
        val filters = listOf(filter1h, filter1d, filter1w, filter1m)
        filters.forEach { btn ->
            btn.setOnClickListener {
                filters.forEach { f ->
                    f.background = null
                    f.setTextColor(resources.getColor(R.color.on_surface_variant, null))
                }
                btn.setBackgroundResource(R.drawable.bg_chip_selected)
                btn.setTextColor(resources.getColor(R.color.on_surface, null))
            }
        }
    }

    private fun observeViewModel() {
        viewModel.wifiKBs.observe(viewLifecycleOwner) { kbs ->
            statsGraph.addDataPoint(kbs, viewModel.simKBs.value ?: 0f)
            updateAvgSpeed()
        }

        viewModel.wifiTotalBytes.observe(viewLifecycleOwner) { bytes ->
            tvWifiTotal.text = viewModel.formatBytes(bytes)
            updateTotalData()
        }

        viewModel.simTotalBytes.observe(viewLifecycleOwner) { bytes ->
            tvSimTotal.text = viewModel.formatBytes(bytes)
            updateTotalData()
        }

        viewModel.maxWifiKBs.observe(viewLifecycleOwner) { _ ->
            updateMaxThroughput()
        }

        viewModel.maxSimKBs.observe(viewLifecycleOwner) { _ ->
            updateMaxThroughput()
        }
    }

    private fun updateTotalData() {
        val total = (viewModel.wifiTotalBytes.value ?: 0L) + (viewModel.simTotalBytes.value ?: 0L)
        tvTotalData.text = viewModel.formatBytes(total)
    }

    private fun updateAvgSpeed() {
        val wifiKBs = viewModel.wifiKBs.value ?: 0f
        val simKBs = viewModel.simKBs.value ?: 0f
        val total = wifiKBs + simKBs
        tvAvgSpeed.text = if (total > 0) "%.1f KB/s".format(total) else "-- KB/s"
    }

    private fun updateMaxThroughput() {
        val maxWifi = viewModel.maxWifiKBs.value ?: 0f
        val maxSim = viewModel.maxSimKBs.value ?: 0f
        val max = maxWifi + maxSim
        tvMaxThroughput.text = if (max > 0) "%.1f KB/s".format(max) else "-- KB/s"
    }
}
