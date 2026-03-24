package com.example.glorytun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.glorytun.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("GlorytunPrefs", MODE_PRIVATE) }

    companion object {
        const val REQUEST_VPN_PERMISSION = 1
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("state")?.let { state ->
                binding.tvStatus.text = "Status: $state"
                if (state == "Disconnected") {
                    binding.btnConnect.text = "Connect"
                } else if (state == "Connected") {
                    binding.btnConnect.text = "Disconnect"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // SharedPreferencesから読み込み
        binding.editServerIp.setText(prefs.getString("IP", ""))
        binding.editServerPort.setText(prefs.getString("PORT", "5000"))
        binding.editSecret.setText(prefs.getString("SECRET", ""))

        registerReceiver(receiver, IntentFilter("VPN_STATE"))

        binding.btnConnect.setOnClickListener {
            if (binding.btnConnect.text == "Disconnect") {
                val serviceIntent = Intent(this, GlorytunVpnService::class.java).apply {
                    action = GlorytunVpnService.ACTION_DISCONNECT
                }
                startService(serviceIntent)
                binding.btnConnect.text = "Connect"
                binding.tvStatus.text = "Status: Disconnecting..."
            } else {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, REQUEST_VPN_PERMISSION)
                } else {
                    onActivityResult(REQUEST_VPN_PERMISSION, RESULT_OK, null)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION && resultCode == RESULT_OK) {
            val ip = binding.editServerIp.text.toString()
            val port = binding.editServerPort.text.toString()
            val secret = binding.editSecret.text.toString()

            // 永続化
            prefs.edit()
                .putString("IP", ip)
                .putString("PORT", port)
                .putString("SECRET", secret)
                .apply()

            val serviceIntent = Intent(this, GlorytunVpnService::class.java).apply {
                action = GlorytunVpnService.ACTION_CONNECT
                putExtra("IP", ip)
                putExtra("PORT", port)
                putExtra("SECRET", secret)
            }
            startService(serviceIntent)
            binding.tvStatus.text = "Status: Connecting..."
            binding.btnConnect.text = "Disconnect"
        } else if (requestCode == REQUEST_VPN_PERMISSION) {
            Toast.makeText(this, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }
}
