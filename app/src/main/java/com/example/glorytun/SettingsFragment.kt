package com.example.glorytun

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<LinearLayout>(R.id.menu_network_protocol)?.setOnClickListener {
            Toast.makeText(requireContext(), "ネットワークプロトコル設定 (準備中)", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<LinearLayout>(R.id.menu_bandwidth)?.setOnClickListener {
            Toast.makeText(requireContext(), "帯域幅管理設定 (準備中)", Toast.LENGTH_SHORT).show()
        }
    }
}
