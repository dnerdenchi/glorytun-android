package com.example.glorytun

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText

class BondingFragment : Fragment() {

    private val viewModel: VpnViewModel by activityViewModels()

    private lateinit var editServerIp: TextInputEditText
    private lateinit var editServerPort: TextInputEditText
    private lateinit var editSecret: TextInputEditText
    private lateinit var fabConnect: ExtendedFloatingActionButton
    private lateinit var tvActiveBadge: TextView
    private lateinit var tvActiveServer: TextView

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            saveAndConnect()
        } else {
            Toast.makeText(requireContext(), "VPN 権限が拒否されました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bonding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editServerIp = view.findViewById(R.id.edit_server_ip)
        editServerPort = view.findViewById(R.id.edit_server_port)
        editSecret = view.findViewById(R.id.edit_secret)
        fabConnect = view.findViewById(R.id.fab_connect)
        tvActiveBadge = view.findViewById(R.id.tv_active_badge)
        tvActiveServer = view.findViewById(R.id.tv_active_server)

        // 保存済みの値を復元
        editServerIp.setText(viewModel.serverIp.value)
        editServerPort.setText(viewModel.serverPort.value)

        viewModel.connectionState.observe(viewLifecycleOwner) { state ->
            val isConnected = state == "Connected"
            if (isConnected) {
                fabConnect.text = "切断"
                tvActiveBadge.text = "接続中"
                tvActiveBadge.setTextColor(resources.getColor(R.color.tertiary, null))
            } else {
                fabConnect.text = "接続"
                tvActiveBadge.text = "未接続"
                tvActiveBadge.setTextColor(resources.getColor(R.color.on_surface_variant, null))
            }
        }

        viewModel.serverIp.observe(viewLifecycleOwner) { ip ->
            val port = viewModel.serverPort.value ?: "5000"
            tvActiveServer.text = if (ip.isNotEmpty()) "$ip:$port" else "サーバー: 未設定"
        }

        fabConnect.setOnClickListener {
            val isConnected = viewModel.connectionState.value == "Connected"
            if (isConnected) {
                disconnectVpn()
            } else {
                val ip = editServerIp.text.toString()
                if (ip.isEmpty()) {
                    Toast.makeText(requireContext(), "サーバー IP を入力してください", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val intent = VpnService.prepare(requireContext())
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    saveAndConnect()
                }
            }
        }
    }

    private fun saveAndConnect() {
        val ip = editServerIp.text.toString()
        val port = editServerPort.text.toString().ifEmpty { "5000" }
        val secret = editSecret.text.toString()

        viewModel.serverIp.value = ip
        viewModel.serverPort.value = port

        // プリファレンスに保存
        (activity as? MainActivity)?.saveCredentials(ip, port, secret)

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
