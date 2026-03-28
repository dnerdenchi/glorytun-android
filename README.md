# Glorytun Android

このプロジェクトは、Android (Kotlin/Java) の `VpnService` を利用し、バックグラウンドの JNI (C/C++) で `glorytun` を動作させるためのベーススケルトンアプリです。

## 技術背景・動作の仕組み

通常のLinux上で `glorytun` は root 権限で `/dev/net/tun` をオープンし通信を行います。
Android（非root環境）では直接 `/dev/net/tun` にアクセスできません。代わりに `VpnService.Builder()` を使ってAndroidシステム側から tun デバイスを開き、そのファイルディスクリプタ(FD)を取得します。

このアプリでは以下の手順で連携します：
1. UI (`MainActivity.kt`) でVPN接続の許可を得る。
2. Service (`GlorytunVpnService.kt`) が `VpnService.Builder()` でVPNを確立し、ファイルディスクリプタ番号(FD)を受け取る。
3. JNI (`glorytun_jni.c`) 経由でFD、サーバーIP、Port 等の設定をC言語層へ渡す。
4. C側で pthread を起動し、その中で本来の `glorytun` のメインループを呼び出す。
   ※ その際、従来 `/dev/net/tun` を open していた部分を、引数で渡された `tun_fd` を利用するように改変（パッチ）しておく必要があります。

## 今後のステップ (ビルドと実行)

すでに `libsodium` の配置、`glorytun` ソースのダウンロード、Cコードパッチの適用、および `CMakeLists.txt` の設定は**自動で完了しています**。

Android Studio でこのプロジェクトを開き、以下の作業を行ってください。

1. **Android Studio でのビルド**
   「Sync Project with Gradle Files」を実行すると、CMakeを通じて自動で `glorytun` と `libsodium` がコンパイル・リンクされます。
   その後、デバイス（またはエミュレータ）にAppをインストールし、VPN接続を行ってください。
   （※ 実運用時は、JNIで引き渡している `glorytun bind` コマンドライン引数をサーバーの要件に合わせて適宜微調整してください）

## サーバー側のセットアップ (Linux / Ubuntu)

サーバー側では `glorytun` をインストールし、以下の設定を行う必要があります。

### 1. glorytun のインストール

`glorytun` をビルドまたはパッケージからインストールしてください。

### 2. シークレットキー (共通鍵) の作成

サーバーとAndroidクライアントで共通の `secret.key` が必要です。

#### A. 自動生成 (推奨)
`glorytun` に付属の `keygen` コマンドを使用して生成します。
```bash
glorytun keygen > secret.key
```

#### B. 手動作成
32バイト（64文字）の16進数文字列をファイルに書き込みます。
```bash
# 例: 任意のヘキサ文字列を直接書き込む場合
echo "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" > secret.key
```

### 3. ネットワーク設定と起動例

サーバーのIPを `1.2.3.4`、ポートを `5000`、共通鍵ファイルを `secret.key` とした場合の例です。

```bash
# キャッシュやバッファなどのチューニング（必要に応じて）
# IP転送を有効化
sudo sysctl -w net.ipv4.ip_forward=1

# glorytun を起動（tun0 インターフェースを作成して待機）
sudo glorytun bind dev tun0 keyfile secret.key

# tun0 にIPアドレスを設定（Android側は 10.0.1.2/32）
sudo ip address add 10.0.1.1 peer 10.0.1.2/32 dev tun0
sudo ip link set tun0 up
```

### 3. MTU と MSS Clamping の設定 (重要)

モバイル回線などでの通信安定化のため、MTUの設定とTCP MSS Clampingの適用を推奨します。

```bash
# MTU を 1420 に設定
sudo ip link set tun0 mtu 1420

# TCP MSS Clamping を適用（VPN経由の大きなTCPパケットを調整）
sudo iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
```

### 4. NAT (マスカレード) 設定

Android側からの通信をインターネットへ転送するために必要です。

```bash
# 10.0.1.2 からの通信を外側のインターフェース（例: eth0）へマスカレード
sudo iptables -t nat -A POSTROUTING -s 10.0.1.2 -j MASQUERADE

# 必要に応じて FORWARD ルールを許可
sudo iptables -A FORWARD -i tun0 -j ACCEPT
sudo iptables -A FORWARD -o tun0 -m state --state RELATED,ESTABLISHED -j ACCEPT
```

---

## 最近の更新

- **VPN接続のフルトンネル化**:
  `GlorytunVpnService.kt` にて、`addRoute("0.0.0.0", 0)` および `addRoute("::", 0)` を設定し、すべてのトラフィックがVPNを経由するように変更しました。また、DNSサーバーとして `8.8.8.8` (Google DNS) と `1.1.1.1` (Cloudflare) を追加設定しました。
- **ルーティングループ防止**:
  JNI層の `gt_socket` フックにより `VpnService.protect(fd)` を呼び出し、VPN自身のパケットがVPNインターフェースに再注入されない（ルーティングループが発生しない）ように対応済みです。
- **マルチパス対応 (WiFi/SIM 併用)**:
  WiFi と SIM (モバイルデータ) の両方のネットワークが存在する場合、両方のパスを使用して通信するように改良しました。JNI層の `mud` ライブラリを拡張し、アクティブなネットワークを動的に検出してパスを追加・削除します。
