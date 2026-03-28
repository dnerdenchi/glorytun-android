package com.example.glorytun

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

class DashboardFragment : Fragment() {

    private val viewModel: VpnViewModel by activityViewModels()

    private lateinit var tvStatus: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvServerInfo: TextView
    private lateinit var tvWifiSpeed: TextView
    private lateinit var tvSimSpeed: TextView
    private lateinit var tvWifiTotal: TextView
    private lateinit var tvSimTotal: TextView
    private lateinit var tvTotalData: TextView
    private lateinit var tvPacketLoss: TextView
    private lateinit var tvLatency: TextView
    private lateinit var btnConnect: Button
    private lateinit var trafficGraph: TrafficGraphView

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startVpnConnection()
        } else {
            Toast.makeText(requireContext(), "VPN 権限が拒否されました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tv_status)
        tvStatusBadge = view.findViewById(R.id.tv_status_badge)
        tvServerInfo = view.findViewById(R.id.tv_server_info)
        tvWifiSpeed = view.findViewById(R.id.tv_wifi_speed)
        tvSimSpeed = view.findViewById(R.id.tv_sim_speed)
        tvWifiTotal = view.findViewById(R.id.tv_wifi_total)
        tvSimTotal = view.findViewById(R.id.tv_sim_total)
        tvTotalData = view.findViewById(R.id.tv_total_data)
        tvPacketLoss = view.findViewById(R.id.tv_packet_loss)
        tvLatency = view.findViewById(R.id.tv_latency)
        btnConnect = view.findViewById(R.id.btn_connect)
        trafficGraph = view.findViewById(R.id.traffic_graph)

        viewModel.connectionState.observe(viewLifecycleOwner) { state ->
            updateConnectionUI(state)
        }

        viewModel.wifiKBs.observe(viewLifecycleOwner) { kbs ->
            tvWifiSpeed.text = if (kbs > 0) "%.1f KB/s".format(kbs) else "-- KB/s"
            trafficGraph.addDataPoint(kbs, viewModel.simKBs.value ?: 0f)
        }

        viewModel.simKBs.observe(viewLifecycleOwner) { kbs ->
            tvSimSpeed.text = if (kbs > 0) "%.1f KB/s".format(kbs) else "-- KB/s"
        }

        viewModel.wifiTotalBytes.observe(viewLifecycleOwner) { bytes ->
            tvWifiTotal.text = "合計: ${viewModel.formatBytes(bytes)}"
            updateTotalData()
        }

        viewModel.simTotalBytes.observe(viewLifecycleOwner) { bytes ->
            tvSimTotal.text = "合計: ${viewModel.formatBytes(bytes)}"
            updateTotalData()
        }

        viewModel.serverIp.observe(viewLifecycleOwner) { ip ->
            val port = viewModel.serverPort.value ?: "5000"
            tvServerInfo.text = if (ip.isNotEmpty()) "サーバー: $ip:$port" else "サーバー: 未設定"
        }

        btnConnect.setOnClickListener {
            val isConnected = viewModel.connectionState.value == "Connected"
            if (isConnected) {
                disconnectVpn()
            } else {
                val intent = VpnService.prepare(requireContext())
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    startVpnConnection()
                }
            }
        }
    }

    private fun updateConnectionUI(state: String) {
        when (state) {
            "Connected" -> {
                tvStatus.text = "接続中"
                tvStatusBadge.text = "接続中"
                tvStatusBadge.setTextColor(resources.getColor(R.color.tertiary, null))
                btnConnect.text = "切断"
            }
            "Connecting..." -> {
                tvStatus.text = "接続しています…"
                tvStatusBadge.text = "接続中…"
                btnConnect.text = "切断"
            }
            else -> {
                tvStatus.text = "未接続"
                tvStatusBadge.text = "未接続"
                tvStatusBadge.setTextColor(resources.getColor(R.color.on_surface_variant, null))
                btnConnect.text = "接続"
                trafficGraph.reset()
            }
        }
    }

    private fun updateTotalData() {
        val total = (viewModel.wifiTotalBytes.value ?: 0L) + (viewModel.simTotalBytes.value ?: 0L)
        tvTotalData.text = viewModel.formatBytes(total)
    }

    private fun startVpnConnection() {
        val ip = viewModel.serverIp.value ?: ""
        val port = viewModel.serverPort.value ?: "5000"
        val secret = (activity as? MainActivity)?.getSecret() ?: ""

        if (ip.isEmpty()) {
            Toast.makeText(requireContext(), "サーバー IP を設定してください", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(requireContext(), GlorytunVpnService::class.java).apply {
            action = GlorytunVpnService.ACTION_CONNECT
            putExtra("IP", ip)
            putExtra("PORT", port)
            putExtra("SECRET", secret)
        }
        requireContext().startService(serviceIntent)
        viewModel.connectionState.value = "Connecting..."
    }

    private fun disconnectVpn() {
        val serviceIntent = Intent(requireContext(), GlorytunVpnService::class.java).apply {
            action = GlorytunVpnService.ACTION_DISCONNECT
        }
        requireContext().startService(serviceIntent)
        viewModel.connectionState.value = "Disconnecting..."
    }
}
