package com.example.glorytun

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.textfield.TextInputEditText

private const val AUTO_CHECK_INTERVAL_MS = 5 * 60 * 1000L  // 5分

class BondingFragment : Fragment() {

    private val viewModel: VpnViewModel by activityViewModels()

    private lateinit var tvActiveBadge: TextView
    private lateinit var tvActiveProfileName: TextView
    private lateinit var tvActiveServer: TextView
    private lateinit var btnQuickConnect: Button
    private lateinit var btnAddProfile: Button
    private lateinit var profileListContainer: LinearLayout
    private lateinit var tvNoProfiles: TextView

    private lateinit var profileRepo: ProfileRepository
    private var profiles = mutableListOf<VpnProfile>()
    private var activeProfileId: String? = null

    // VPN接続時に使用するプロファイル（Permission確認後に接続するため保持）
    private var pendingProfile: VpnProfile? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingProfile?.let { connectWithProfile(it) }
        } else {
            Toast.makeText(requireContext(), "VPN 権限が拒否されました", Toast.LENGTH_SHORT).show()
        }
        pendingProfile = null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bonding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profileRepo = ProfileRepository(requireContext())

        tvActiveBadge = view.findViewById(R.id.tv_active_badge)
        tvActiveProfileName = view.findViewById(R.id.tv_active_profile_name)
        tvActiveServer = view.findViewById(R.id.tv_active_server)
        btnQuickConnect = view.findViewById(R.id.btn_quick_connect)
        btnAddProfile = view.findViewById(R.id.btn_add_profile)
        profileListContainer = view.findViewById(R.id.profile_list_container)
        tvNoProfiles = view.findViewById(R.id.tv_no_profiles)

        // 接続状態の監視
        viewModel.connectionState.observe(viewLifecycleOwner) { state ->
            val isConnected = state == "Connected"
            if (isConnected) {
                tvActiveBadge.text = "接続済み"
                tvActiveBadge.setTextColor(resources.getColor(R.color.tertiary, null))
                btnQuickConnect.text = "切断"
            } else {
                tvActiveBadge.text = "未接続"
                tvActiveBadge.setTextColor(resources.getColor(R.color.on_surface_variant, null))
                btnQuickConnect.text = "接続"
            }
            // アクティブ表示を更新
            refreshActiveIndicators()
        }

        // サーバー情報の監視
        viewModel.serverIp.observe(viewLifecycleOwner) { ip ->
            val port = viewModel.serverPort.value ?: "5000"
            tvActiveServer.text = if (ip.isNotEmpty()) "$ip:$port" else "サーバー: 未設定"
        }

        btnAddProfile.setOnClickListener {
            showProfileDialog(null)
        }

        btnQuickConnect.setOnClickListener {
            val isConnected = viewModel.connectionState.value == "Connected"
            if (isConnected) {
                disconnectVpn()
            } else {
                val activeProfile = profiles.find { it.id == activeProfileId }
                if (activeProfile == null) {
                    Toast.makeText(requireContext(), "プロファイルを選択してください", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                requestVpnAndConnect(activeProfile)
            }
        }

        loadProfilesAndRefresh()
    }

    override fun onResume() {
        super.onResume()
        // フラグメント表示時に5分以上経過したプロファイルを自動確認
        autoCheckIfNeeded()
    }

    private fun loadProfilesAndRefresh() {
        profiles = profileRepo.loadProfiles()
        activeProfileId = profileRepo.getActiveProfileId()

        // アクティブプロファイルの情報をViewModelに反映
        val activeProfile = profiles.find { it.id == activeProfileId }
        if (activeProfile != null) {
            tvActiveProfileName.text = activeProfile.name
            (activity as? MainActivity)?.loadProfile(activeProfile)
        } else if (profiles.isNotEmpty()) {
            // アクティブ未設定なら先頭を選択
            selectProfile(profiles[0], connect = false)
        } else {
            tvActiveProfileName.text = "プロファイル未選択"
        }

        refreshProfileList()
    }

    private fun refreshProfileList() {
        profileListContainer.removeAllViews()

        if (profiles.isEmpty()) {
            tvNoProfiles.visibility = View.VISIBLE
            return
        }

        tvNoProfiles.visibility = View.GONE
        val inflater = LayoutInflater.from(requireContext())

        profiles.forEach { profile ->
            val itemView = inflater.inflate(R.layout.item_vpn_profile, profileListContainer, false)

            itemView.findViewById<TextView>(R.id.tv_profile_name).text = profile.name
            itemView.findViewById<TextView>(R.id.tv_profile_server).text = "${profile.ip}:${profile.port}"

            val activeIndicator = itemView.findViewById<TextView>(R.id.tv_active_indicator)
            activeIndicator.visibility = if (profile.id == activeProfileId) View.VISIBLE else View.GONE

            val tvStatus = itemView.findViewById<TextView>(R.id.tv_server_status)
            val btnCheck = itemView.findViewById<Button>(R.id.btn_check_server)

            // キャッシュがあれば結果を復元
            restoreCachedStatus(profile, tvStatus)

            btnCheck.setOnClickListener {
                runServerCheck(profile, btnCheck, tvStatus)
            }

            itemView.findViewById<Button>(R.id.btn_connect_profile).setOnClickListener {
                selectProfile(profile, connect = true)
            }

            itemView.findViewById<Button>(R.id.btn_edit_profile).setOnClickListener {
                showProfileDialog(profile)
            }

            itemView.findViewById<Button>(R.id.btn_delete_profile).setOnClickListener {
                showDeleteConfirmDialog(profile)
            }

            profileListContainer.addView(itemView)
        }
    }

    private fun refreshActiveIndicators() {
        for (i in 0 until profileListContainer.childCount) {
            val itemView = profileListContainer.getChildAt(i)
            val profile = profiles.getOrNull(i) ?: continue
            val indicator = itemView.findViewById<TextView>(R.id.tv_active_indicator)
            indicator.visibility = if (profile.id == activeProfileId) View.VISIBLE else View.GONE
        }
    }

    /** プロファイルをアクティブにし、必要なら接続する */
    private fun selectProfile(profile: VpnProfile, connect: Boolean) {
        activeProfileId = profile.id
        profileRepo.setActiveProfileId(profile.id)

        tvActiveProfileName.text = profile.name
        (activity as? MainActivity)?.loadProfile(profile)

        refreshActiveIndicators()

        if (connect) {
            requestVpnAndConnect(profile)
        }
    }

    private fun requestVpnAndConnect(profile: VpnProfile) {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            pendingProfile = profile
            vpnPermissionLauncher.launch(intent)
        } else {
            connectWithProfile(profile)
        }
    }

    private fun connectWithProfile(profile: VpnProfile) {
        val serviceIntent = Intent(requireContext(), GlorytunVpnService::class.java).apply {
            action = GlorytunConstants.ACTION_CONNECT
            putExtra("IP", profile.ip)
            putExtra("PORT", profile.port)
            putExtra("SECRET", profile.secret)
        }
        requireContext().startService(serviceIntent)
        viewModel.connectionState.value = "Connecting..."
    }

    private fun disconnectVpn() {
        val serviceIntent = Intent(requireContext(), GlorytunVpnService::class.java).apply {
            action = GlorytunConstants.ACTION_DISCONNECT
        }
        requireContext().startService(serviceIntent)
        viewModel.connectionState.value = "Disconnecting..."
    }

    /**
     * バックグラウンドスレッドでサーバー疎通確認を実行し、結果をUIに反映する。
     * TCPポートに接続を試みて応答時間（RTT目安）を計測する。
     * glorytunはUDPなのでTCP接続は確立されないが、接続拒否（ECONNREFUSED）が
     * 返れば「到達可能」と判定し、その応答時間をレイテンシの目安として表示する。
     */
    private fun runServerCheck(profile: VpnProfile, btnCheck: Button, tvStatus: TextView) {
        btnCheck.isEnabled = false
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(resources.getColor(R.color.on_surface_variant, null))
        tvStatus.text = "確認中…"

        val port = profile.port.toIntOrNull() ?: 5000
        Thread {
            val result = ServerChecker.check(profile.ip, port, timeoutMs = 5000)
            val entry = ServerCheckEntry(
                checkedAt = System.currentTimeMillis(),
                reachable = result.reachable,
                detail = result.detail,
                rttMs = result.rttMs
            )
            viewModel.serverCheckCache[profile.id] = entry
            Handler(Looper.getMainLooper()).post {
                if (!isAdded) return@post
                btnCheck.isEnabled = true
                applyStatusText(tvStatus, entry)
            }
        }.start()
    }

    /** キャッシュから応答確認結果を復元してUIに反映する */
    private fun restoreCachedStatus(profile: VpnProfile, tvStatus: TextView) {
        val entry = viewModel.serverCheckCache[profile.id] ?: return
        tvStatus.visibility = View.VISIBLE
        applyStatusText(tvStatus, entry)
    }

    /** ServerCheckEntry の内容をtvStatusに表示する（前回確認時刻付き） */
    private fun applyStatusText(tvStatus: TextView, entry: ServerCheckEntry) {
        val agoText = elapsedText(entry.checkedAt)
        if (entry.reachable) {
            tvStatus.setTextColor(resources.getColor(R.color.tertiary, null))
            tvStatus.text = "✓ ${entry.detail}  ${entry.rttMs}ms  $agoText"
        } else {
            tvStatus.setTextColor(resources.getColor(R.color.error, null))
            tvStatus.text = "✗ ${entry.detail}  $agoText"
        }
    }

    /** 経過時間を「X分前」「X秒前」などの文字列に変換する */
    private fun elapsedText(timestamp: Long): String {
        val elapsed = System.currentTimeMillis() - timestamp
        return when {
            elapsed < 60_000L -> "${elapsed / 1000}秒前"
            elapsed < 3_600_000L -> "${elapsed / 60_000}分前"
            else -> "${elapsed / 3_600_000}時間前"
        }
    }

    /** 5分以上経過したプロファイルをバックグラウンドで自動確認する */
    private fun autoCheckIfNeeded() {
        if (!isAdded) return
        val now = System.currentTimeMillis()
        profiles.forEachIndexed { index, profile ->
            val cached = viewModel.serverCheckCache[profile.id]
            if (cached != null && now - cached.checkedAt < AUTO_CHECK_INTERVAL_MS) return@forEachIndexed

            // 対応するitemViewを見つけてUIを更新
            val itemView = profileListContainer.getChildAt(index) ?: return@forEachIndexed
            val tvStatus = itemView.findViewById<TextView>(R.id.tv_server_status) ?: return@forEachIndexed
            val btnCheck = itemView.findViewById<Button>(R.id.btn_check_server) ?: return@forEachIndexed
            runServerCheck(profile, btnCheck, tvStatus)
        }
    }

    /** プロファイル追加/編集ダイアログを表示 */
    private fun showProfileDialog(existingProfile: VpnProfile?) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_profile, null)

        val editName = dialogView.findViewById<TextInputEditText>(R.id.edit_profile_name)
        val editIp = dialogView.findViewById<TextInputEditText>(R.id.edit_profile_ip)
        val editPort = dialogView.findViewById<TextInputEditText>(R.id.edit_profile_port)
        val editSecret = dialogView.findViewById<TextInputEditText>(R.id.edit_profile_secret)

        if (existingProfile != null) {
            editName.setText(existingProfile.name)
            editIp.setText(existingProfile.ip)
            editPort.setText(existingProfile.port)
            editSecret.setText(existingProfile.secret)
        }

        val title = if (existingProfile == null) "プロファイルを追加" else "プロファイルを編集"
        val positiveText = if (existingProfile == null) "追加" else "更新"

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(positiveText) { _, _ ->
                val name = editName.text.toString().trim().ifEmpty { "プロファイル" }
                val ip = editIp.text.toString().trim()
                val port = editPort.text.toString().trim().ifEmpty { "5000" }
                val secret = editSecret.text.toString()

                if (ip.isEmpty()) {
                    Toast.makeText(requireContext(), "サーバー IP を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (existingProfile == null) {
                    profiles.add(VpnProfile(name = name, ip = ip, port = port, secret = secret))
                } else {
                    val idx = profiles.indexOfFirst { it.id == existingProfile.id }
                    if (idx >= 0) {
                        profiles[idx] = existingProfile.copy(name = name, ip = ip, port = port, secret = secret)
                        // アクティブプロファイルが更新された場合はViewModelにも反映
                        if (existingProfile.id == activeProfileId) {
                            (activity as? MainActivity)?.loadProfile(profiles[idx])
                        }
                    }
                }

                profileRepo.saveProfiles(profiles)
                refreshProfileList()
            }

        if (existingProfile != null) {
            builder.setNeutralButton("削除") { _, _ ->
                showDeleteConfirmDialog(existingProfile)
            }
        }

        builder.setNegativeButton("キャンセル", null)
            .show()
    }

    /** 削除確認ダイアログを表示 */
    private fun showDeleteConfirmDialog(profile: VpnProfile) {
        AlertDialog.Builder(requireContext())
            .setTitle("プロファイルを削除")
            .setMessage("「${profile.name}」を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                profiles.removeAll { it.id == profile.id }
                profileRepo.saveProfiles(profiles)

                if (activeProfileId == profile.id) {
                    val next = profiles.firstOrNull()
                    if (next != null) {
                        selectProfile(next, connect = false)
                    } else {
                        activeProfileId = null
                        profileRepo.setActiveProfileId(null)
                        tvActiveProfileName.text = "プロファイル未選択"
                        tvActiveServer.text = "サーバー: 未設定"
                    }
                }

                refreshProfileList()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
