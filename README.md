# Glorytun Android

Android (Kotlin + JNI/C) で [glorytun](https://github.com/angt/glorytun) を動作させる VPN アプリです。
`VpnService` API を利用して非 root 環境で TUN デバイスを確立し、WiFi と SIM カードの **マルチパス同時通信**に対応しています。

---

## 特徴

| 機能 | 詳細 |
|------|------|
| **暗号化 VPN トンネル** | glorytun + libsodium による AEGIS-256 / ChaCha20-Poly1305 暗号化 |
| **マルチパス対応** | WiFi と SIM カードを同時使用し帯域を束ねる (Phase 2 実装済み) |
| **フルトンネル** | 全トラフィックを VPN 経由でルーティング (IPv4/IPv6) |
| **ループ防止** | `VpnService.protect()` により VPN パケット自体の再注入を防止 |
| **設定の永続化** | `EncryptedSharedPreferences` でサーバー情報を安全に保存 |

---

## アーキテクチャ概要

通常の Linux では glorytun が root 権限で `/dev/net/tun` を直接開きますが、Android (非 root) ではこれができません。
本アプリは `VpnService.Builder` で TUN の fd を取得し、JNI を介して C 層へ渡すことでこれを回避しています。

```
MainActivity
  └─ GlorytunVpnService (VpnService)
       ├─ VpnService.Builder.establish() → TUN fd 取得
       ├─ startGlorytunNative(fd, ip, port, secret)  ← JNI
       │    └─ pthread → glorytun_main → gt_bind (select ループ)
       └─ NetworkCallback (WiFi / SIM)
            ├─ addPathForNetwork()    ← JNI: mud にパス追加
            └─ removePathForNetwork() ← JNI: mud からパス削除
```

### 技術スタック

- **言語**: Kotlin (Android) + C (glorytun/mud コア)
- **ビルド**: Gradle + CMake (Android NDK)
- **対象 ABI**: `arm64-v8a`
- **最小 SDK**: 24 (Android 7.0)
- **ターゲット SDK**: 34 (Android 14)

---

## ビルド方法

### 前提条件

- Android Studio (最新版推奨)
- Android NDK (CMake 3.22.1 以上)
- `libsodium.a` が `app/src/main/cpp/libs/arm64-v8a/` に配置済みであること

### 手順

1. このリポジトリをクローン (サブモジュールごと)

   ```bash
   git clone --recurse-submodules <repo-url>
   ```

2. Android Studio でプロジェクトを開き、**Sync Project with Gradle Files** を実行

3. CMake が自動的に glorytun と libsodium をコンパイル・リンクします

4. デバイスまたはエミュレータにインストールして動作確認

> **注意**: JNI 側で glorytun に渡すコマンドライン引数はサーバーの設定に合わせて調整してください。

---

## サーバー側のセットアップ (Linux / Ubuntu)

### 1. glorytun のインストール

```bash
# ソースからビルド、またはパッケージマネージャ経由でインストール
```

### 2. シークレットキーの作成

**推奨 (自動生成)**

```bash
glorytun keygen > secret.key
```

**手動作成**

```bash
# 32バイト = 64文字の hex 文字列
echo "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" > secret.key
```

### 3. サーバー起動

サーバー IP: `1.2.3.4`、ポート: `5000`、Android 側 IP: `10.0.1.2` の例:

```bash
# IP フォワーディングを有効化
sudo sysctl -w net.ipv4.ip_forward=1

# glorytun 起動 (tun0 を作成して待機)
sudo glorytun bind dev tun0 keyfile secret.key

# IP アドレスの設定
sudo ip address add 10.0.1.1 peer 10.0.1.2/32 dev tun0
sudo ip link set tun0 up

# MTU 設定
sudo ip link set tun0 mtu 1420
```

### 4. ファイアウォール設定

```bash
# TCP MSS Clamping (モバイル回線での安定化)
sudo iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu

# NAT (Android からのトラフィックをインターネットへ転送)
sudo iptables -t nat -A POSTROUTING -s 10.0.1.2 -j MASQUERADE
sudo iptables -A FORWARD -i tun0 -j ACCEPT
sudo iptables -A FORWARD -o tun0 -m state --state RELATED,ESTABLISHED -j ACCEPT
```

---

## マルチパス動作 (WiFi + SIM 同時使用)

```
GlorytunVpnService
  ├─ wifiCallback.onAvailable  → addPathForNetwork(wifiLocalIp,  wifiHandle)
  ├─ wifiCallback.onLost       → removePathForNetwork(wifiLocalIp)
  ├─ cellCallback.onAvailable  → addPathForNetwork(cellLocalIp,  cellHandle)
  └─ cellCallback.onLost       → removePathForNetwork(cellLocalIp)

addPathForNetwork (JNI):
  1. UDP ソケットを新規作成
  2. VpnService.protect() でルーティングループ防止
  3. Network.bindSocket() で特定ネットワークに紐付け
  4. mud_set_path_socket() で mud の epoll に登録

mud (C):
  - 各パスを epoll で監視
  - RTT・帯域幅に基づいてパスを動的に選択
  - 最大 32 パスをサポート
```

---

## Android 権限

| 権限 | 用途 |
|------|------|
| `INTERNET` | VPN トンネル通信 |
| `ACCESS_NETWORK_STATE` | ネットワーク状態の取得 |
| `CHANGE_NETWORK_STATE` | マルチパス用ネットワーク要求 |
| `FOREGROUND_SERVICE` | バックグラウンドでの VPN 維持 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14+ 対応 |

---

## 依存ライブラリ

| ライブラリ | 用途 |
|-----------|------|
| [libsodium](https://libsodium.org/) | 暗号化 (ChaCha20-Poly1305 / AEGIS-256) |
| [glorytun](https://github.com/angt/glorytun) | マルチパス UDP VPN コア |
| `androidx.security:security-crypto` | 設定情報の暗号化保存 |
| `com.google.android.material` | Material Design UI |
