package com.example.glorytun

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class PairShareFragment : Fragment() {
    private lateinit var repository: PairShareRepository
    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var switchSharing: SwitchMaterial
    private lateinit var switchReceiving: SwitchMaterial
    private lateinit var status: TextView
    private lateinit var pairingCode: TextView
    private lateinit var displayName: EditText
    private lateinit var controls: View
    private lateinit var pendingTitle: TextView
    private lateinit var discoveredTitle: TextView
    private lateinit var peersTitle: TextView
    private lateinit var pendingContainer: LinearLayout
    private lateinit var discoveredContainer: LinearLayout
    private lateinit var peersContainer: LinearLayout

    private var rendering = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_pair_share, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = PairShareRepository(requireContext())
        switchEnabled = view.findViewById(R.id.switch_pair_share_enabled)
        switchSharing = view.findViewById(R.id.switch_pair_share_sharing)
        switchReceiving = view.findViewById(R.id.switch_pair_share_receiving)
        status = view.findViewById(R.id.tv_pair_share_status)
        pairingCode = view.findViewById(R.id.tv_pair_share_code)
        displayName = view.findViewById(R.id.et_pair_share_display_name)
        controls = view.findViewById(R.id.pair_share_controls)
        pendingTitle = view.findViewById(R.id.tv_pair_share_pending_title)
        discoveredTitle = view.findViewById(R.id.tv_pair_share_discovered_title)
        peersTitle = view.findViewById(R.id.tv_pair_share_peers_title)
        pendingContainer = view.findViewById(R.id.pair_share_pending_container)
        discoveredContainer = view.findViewById(R.id.pair_share_discovered_container)
        peersContainer = view.findViewById(R.id.pair_share_peers_container)

        switchEnabled.setOnCheckedChangeListener { _, enabled ->
            if (rendering) return@setOnCheckedChangeListener
            if (enabled) PairShareService.start(requireContext())
            else PairShareService.stop(requireContext())
        }
        switchSharing.setOnCheckedChangeListener { _, enabled ->
            if (rendering) return@setOnCheckedChangeListener
            repository.setSharingEnabled(enabled)
            PairShareService.refresh(requireContext())
        }
        switchReceiving.setOnCheckedChangeListener { _, enabled ->
            if (rendering) return@setOnCheckedChangeListener
            repository.setReceivingEnabled(enabled)
            if (!enabled) PairShareCoordinator.closeClient()
            PairShareService.refresh(requireContext())
        }
        displayName.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                repository.setDisplayName(displayName.text?.toString().orEmpty())
                PairShareService.refresh(requireContext())
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_pair_share_regenerate_code).setOnClickListener {
            PairShareService.regenerateCode(requireContext())
        }

        PairShareCoordinator.state.observe(viewLifecycleOwner) { render(it) }
        renderLocalState()
    }

    override fun onResume() {
        super.onResume()
        if (repository.isEnabled()) PairShareService.start(requireContext())
        else renderLocalState()
    }

    private fun renderLocalState() {
        render(
            PairShareUiState(
                enabled = repository.isEnabled(),
                serviceStatus = if (repository.isEnabled()) "Pair & Share を開始しています" else "Pair & Share は無効です",
                pairingCode = repository.pairingCode(),
                peers = repository.peers(),
            ),
        )
    }

    private fun render(state: PairShareUiState) {
        if (!isAdded) return
        rendering = true
        try {
            switchEnabled.isChecked = state.enabled
            switchSharing.isChecked = repository.isSharingEnabled()
            switchReceiving.isChecked = repository.isReceivingEnabled()
            status.text = state.serviceStatus
            pairingCode.text = state.pairingCode.chunked(3).joinToString(" ")
            if (!displayName.hasFocus()) displayName.setText(repository.displayName())
            controls.visibility = if (state.enabled) View.VISIBLE else View.GONE
            pendingTitle.visibility = if (state.enabled) View.VISIBLE else View.GONE
            discoveredTitle.visibility = if (state.enabled) View.VISIBLE else View.GONE
            peersTitle.visibility = if (state.enabled) View.VISIBLE else View.GONE
            renderPending(state.pending)
            renderDiscoveries(state.discovered)
            renderPeers(state.peers, state.peerStats)
        } finally {
            rendering = false
        }
    }

    private fun renderPending(items: List<PairSharePending>) {
        pendingContainer.removeAllViews()
        if (items.isEmpty()) {
            pendingContainer.addView(emptyText("現在、承認待ちの要求はありません。"))
            return
        }
        items.forEach { item ->
            val card = card()
            card.addView(title(item.displayName))
            card.addView(subtitle("確認用の3語: ${item.verificationWords}"))
            card.addView(subtitle("端末名と 6桁コードが相手側の表示と一致することを確認してください。"))
            val actions = actionRow()
            actions.addView(button("承認") {
                PairShareService.acceptPair(requireContext(), item.requestId)
            })
            actions.addView(button("拒否", outlined = true) {
                PairShareService.rejectPair(requireContext(), item.requestId)
            })
            card.addView(actions)
            pendingContainer.addView(card)
        }
    }

    private fun renderDiscoveries(items: List<PairShareDiscovery>) {
        discoveredContainer.removeAllViews()
        if (items.isEmpty()) {
            discoveredContainer.addView(emptyText("同じ Wi-Fi 上の未ペア端末はまだ見つかっていません。"))
            return
        }
        items.forEach { discovery ->
            val card = card()
            card.addView(title(discovery.displayName))
            card.addView(subtitle("同じ Wi-Fi 上で検出済み"))
            val actions = actionRow()
            actions.addView(button("6桁コードでペアリング") { showPairDialog(discovery) })
            card.addView(actions)
            discoveredContainer.addView(card)
        }
    }

    private fun renderPeers(
        items: List<PairSharePeer>,
        statsByPeer: Map<String, PairSharePeerStats>,
    ) {
        peersContainer.removeAllViews()
        if (items.isEmpty()) {
            peersContainer.addView(emptyText("ペア端末はまだありません。近くの端末を選んでペアリングしてください。"))
            return
        }
        items.forEach { peer ->
            val card = card()
            card.addView(title(peer.displayName))
            val priority = repository.pathPriority(peer)
            card.addView(subtitle("パス優先度: " + priority.displayName))
            val availability = if (peer.address != null && peer.port > 0) "同じ Wi-Fi 上で検出済み" else "同じ Wi-Fi 上で再検出待ち"
            card.addView(subtitle(availability))
            val sharingLabel = when {
                !peer.canUseMyConnection -> "この端末からの共有: 停止中"
                peer.speedLimitMbps == 0 -> "この端末からの共有: 許可済み（上限なし）"
                else -> "この端末からの共有: 許可済み（${peer.speedLimitMbps} Mbps）"
            }
            card.addView(subtitle(sharingLabel))
            statsByPeer[peer.id]?.let { stats ->
                card.addView(subtitle(formatPeerStats(stats)))
            }
            val actions = actionRow()
            actions.addView(button(if (peer.canUseMyConnection) "共有設定" else "共有を許可") {
                showSharePermissionDialog(peer)
            })
            actions.addView(button("パス設定", outlined = repository.pathPriority(peer) == PairBondPathPriority.DISABLED) {
                usePeerForReceiving(peer)
            })
            actions.addView(button("解除", outlined = true) { showUnpairDialog(peer) })
            card.addView(actions)
            peersContainer.addView(card)
        }
    }

    private fun formatPeerStats(stats: PairSharePeerStats): String {
        val rtt = stats.rttMillis?.let { it.toString() + " ms" } ?: "--"
        val loss = (stats.lossPermille / 10.0).toString() + "%"
        return "状態: " + stats.status +
            "  ↑" + formatRate(stats.txBytesPerSecond) +
            "  ↓" + formatRate(stats.rxBytesPerSecond) +
            "  RTT " + rtt +
            "  損失 " + loss +
            "  再送 " + stats.retransmissions +
            "\n累計 ↑" + formatBytes(stats.txBytes) + "  ↓" + formatBytes(stats.rxBytes)
    }

    private fun formatRate(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> (bytes / 1_000_000_000L).toString() + " GB"
        bytes >= 1_000_000L -> (bytes / 1_000L).toString() + " MB"
        bytes >= 1_000L -> (bytes / 1_000L).toString() + " KB"
        else -> bytes.toString() + " B"
    }

    private fun showPathPriorityDialog(peer: PairSharePeer) {
        val priorities = arrayOf(
            PairBondPathPriority.ACTIVE,
            PairBondPathPriority.BACKUP_ONLY,
            PairBondPathPriority.DISABLED,
        )
        val labels = arrayOf(
            "自動ボンディング（同時に使用）",
            "バックアップ専用（障害時のみ使用）",
            "使用しない",
        )
        var selected = priorities.indexOf(repository.pathPriority(peer)).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(peer.displayName + " のパス設定")
            .setMessage("複数の「自動ボンディング」パスは同時に使用します。バックアップ専用は通常のパスが利用不能な時だけ使います。")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val priority = priorities[selected]
                repository.setPeerPathPriority(peer.id, priority)
                if (priority != PairBondPathPriority.DISABLED) {
                    repository.setReceivingEnabled(true)
                    requireContext().getSharedPreferences(GlorytunConstants.PREFS_PROXY, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(GlorytunConstants.KEY_ADGUARD_PROXY_MODE_ENABLED, true)
                        .apply()
                }
                PairShareCoordinator.refreshBondingPaths(requireContext())
                PairShareService.refresh(requireContext())
            }
            .show()
    }

    private fun showPairDialog(discovery: PairShareDiscovery) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6桁コード"
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("${discovery.displayName} とペアリング")
            .setMessage("相手の Pair & Share 画面に表示された6桁コードを入力してください。相手側で承認が必要です。")
            .setView(input)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("要求を送信") { _, _ ->
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.length != 6 || code.any { !it.isDigit() }) {
                    Toast.makeText(requireContext(), "6桁の数字を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                PairShareService.requestPair(requireContext(), discovery, code)
            }
            .show()
    }

    private fun showSharePermissionDialog(peer: PairSharePeer) {
        val limits = intArrayOf(1, 5, 10, 25, 50, 0)
        val labels = arrayOf("1 Mbps", "5 Mbps", "10 Mbps", "25 Mbps", "50 Mbps", "上限なし")
        var selected = limits.indexOf(peer.speedLimitMbps).takeIf { it >= 0 } ?: 2
        AlertDialog.Builder(requireContext())
            .setTitle("${peer.displayName} への共有")
            .setMessage("共有を有効にすると、相手端末はこの端末のSIM回線を PairBond の暗号化パスとして利用できます。上限はこの端末で強制します。")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(if (peer.canUseMyConnection) "共有を停止" else "キャンセル") { _, _ ->
                if (peer.canUseMyConnection) {
                    repository.setPeerSharePermission(peer.id, false, peer.speedLimitMbps)
                    PairShareService.refresh(requireContext())
                }
            }
            .setPositiveButton("保存") { _, _ ->
                repository.setPeerSharePermission(peer.id, true, limits[selected])
                PairShareService.refresh(requireContext())
            }
            .show()
    }

    private fun usePeerForReceiving(peer: PairSharePeer) {
        showPathPriorityDialog(peer)
    }

    private fun showUnpairDialog(peer: PairSharePeer) {
        AlertDialog.Builder(requireContext())
            .setTitle("${peer.displayName} を解除しますか？")
            .setMessage("この端末との共有・受信の許可を削除します。もう一度使うには再ペアリングが必要です。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("ペア解除") { _, _ ->
                repository.removePeer(peer.id)
                PairShareCoordinator.closeClient()
                PairShareService.refresh(requireContext())
            }
            .show()
    }

    private fun card(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_card)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun title(value: String) = TextView(requireContext()).apply {
        text = value
        setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun subtitle(value: String) = TextView(requireContext()).apply {
        text = value
        setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
    }

    private fun emptyText(value: String) = TextView(requireContext()).apply {
        text = value
        setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
        textSize = 13f
        setPadding(dp(4), dp(6), dp(4), dp(10))
    }

    private fun actionRow() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
    }

    private fun button(label: String, outlined: Boolean = false, onClick: () -> Unit) =
        MaterialButton(requireContext(), null, if (outlined) {
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        } else {
            com.google.android.material.R.attr.materialButtonStyle
        }).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
