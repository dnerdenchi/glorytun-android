# BondVPN Android

BondVPN は Android の `VpnService` 上で [mqvpn](https://github.com/mp0rta/mqvpn) を動かす VPN アプリです。mqvpn は MASQUE CONNECT-IP と Multipath QUIC を使い、Wi-Fi やモバイル回線など複数の経路を 1 本の VPN トンネルとして扱えます。

## 特徴

- Android の VPN 機能を使って mqvpn トンネルへ接続します。
- mqvpn の Multipath QUIC / WLB UDP flow pin scheduler により、動画通信の順序を保ちながら複数回線を利用します。
- GitHub Releases の APK asset を参照するアプリ内更新機能を備えています。
- 現在の native bridge は `arm64-v8a` 向けです。

## 動作環境

| 項目 | 内容 |
| --- | --- |
| VPN エンジン | mqvpn v0.7.0 |
| Android 側 | Kotlin + mqvpn Android SDK source |
| Native 側 | mqvpn Android 向け `libmqvpn_jni.so` |
| 対応 ABI | `arm64-v8a` |
| 最小 Android | API 26 |
| アプリ更新 | GitHub Releases の APK asset |

## Android アプリのビルド

このリポジトリでは Gradle wrapper が無い環境でも、ローカルに Gradle と JDK が入っていればビルドできます。Windows で Android Studio 付属 JBR を使う例です。

```powershell
$env:JAVA_HOME="D:\Soft\AndroidStudio\jbr"
gradle assembleDebug
```

生成される APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

テストも実行する場合:

```powershell
$env:JAVA_HOME="D:\Soft\AndroidStudio\jbr"
gradle test assembleDebug
```

## Android アプリの接続設定

アプリの接続プロファイルには、mqvpn サーバーの情報を登録します。

| 入力 | 説明 |
| --- | --- |
| サーバーアドレス | mqvpn サーバーの IP アドレスまたは DNS 名 |
| ポート番号 | サーバー側で待ち受ける UDP ポート。標準例は `443` |
| mqvpn 認証キー | サーバーの `/etc/mqvpn/server.conf` にある `[Auth] Key` の値 |
| 自己署名証明書を許可する | install script 標準の自己署名証明書を使う場合はオン |

Let's Encrypt などの信頼済み証明書をサーバーに設定している場合は、「自己署名証明書を許可する」をオフにしてください。

## PairBond: 複数ペアSIM端末のプロキシ・ボンディング

Pair & Share は、同じ Wi-Fi にいる複数のペア端末のモバイル回線を、プロキシモードで同時に使う PairBond を備えています。

- 各ペア端末を「自動ボンディング」「バックアップ専用」「使用しない」に設定できます。
- 自動ボンディングのパスは、RTT・ACK応答・再送率・実効転送量を使ってチャンク単位で自動配分します。
- バックアップ専用は通常時に通信を流さず、アクティブパスが途切れた時に自動で引き継ぎます。
- TCP はサーバーで順序復元し、未ACKチャンクを別パスへ再送します。SOCKS5 TCP、HTTP CONNECT、SOCKS5 UDP ASSOCIATE に対応します。
- Pair & Share 画面には、ペアごとの優先度、状態、送受信量、瞬間速度、RTT、損失、再送回数を表示します。

利用前に、サーバーへ PairBond リレーを配備してください。配備手順は [server/pairbond-relay/README.md](server/pairbond-relay/README.md) を参照してください。リレーは mqvpn と同じ [Auth] Key を安全に読み取り、TCP 443 だけを追加で待ち受けます。

## mqvpn サーバーのセットアップ

Ubuntu / Debian 系の VPS に mqvpn サーバーを建てる例です。以下では UDP 443 を使います。

### 1. OS を更新する

```bash
sudo apt update
sudo apt upgrade -y
```

Ubuntu 24.04 で `libevent-2.1.so.7` が見つからない場合は、mqvpn の実行に必要な libevent を追加します。

```bash
sudo apt install -y libevent-2.1-7t64 libevent-pthreads-2.1-7t64
```

### 2. mqvpn をインストールする

公式 install script で最新 release をインストールし、systemd service として起動します。

```bash
curl -fsSL https://github.com/mp0rta/mqvpn/releases/latest/download/install.sh \
  | sudo bash -s -- --start --port 443 --subnet 10.8.0.0/24
```

この手順で主に次のファイルと service が作られます。

| パス / service | 内容 |
| --- | --- |
| `/usr/local/bin/mqvpn` | mqvpn 本体 |
| `/etc/mqvpn/server.conf` | サーバー設定 |
| `/etc/mqvpn/server.crt` | TLS 証明書 |
| `/etc/mqvpn/server.key` | TLS 秘密鍵 |
| `mqvpn-server.service` | systemd service |

### 3. UDP ポートを開ける

UFW を使っている場合:

```bash
sudo ufw allow 443/udp
sudo ufw reload
```

VPS 事業者側の security group / firewall でも UDP 443 を許可してください。

### 4. 認証キーを確認する

```bash
sudo grep -A3 '^\[Auth\]' /etc/mqvpn/server.conf
```

`Key = ...` の値を Android アプリの「mqvpn 認証キー」に入力します。

### 5. 起動状態を確認する

```bash
sudo systemctl status mqvpn-server --no-pager
sudo journalctl -u mqvpn-server -n 100 --no-pager
```

ログを追い続ける場合:

```bash
sudo journalctl -u mqvpn-server -f
```

## サーバー再起動後の起動方法

install script に `--start` を付けて実行した場合、`mqvpn-server` は systemd service として登録されます。自動起動が有効になっていれば、VPS を再起動しても mqvpn は自動的に起動します。

自動起動が有効か確認します。

```bash
sudo systemctl is-enabled mqvpn-server
```

`enabled` ではない場合は、自動起動を有効化します。

```bash
sudo systemctl enable mqvpn-server
```

サーバー再起動後に手動で起動する場合:

```bash
sudo systemctl start mqvpn-server
```

再起動後の確認:

```bash
sudo systemctl status mqvpn-server --no-pager
sudo ss -lunp | grep ':443'
```

設定を変更したあとに mqvpn だけ再起動する場合:

```bash
sudo systemctl restart mqvpn-server
```

## サーバー設定の変更

サーバー設定は `/etc/mqvpn/server.conf` にあります。変更後は service を再起動してください。

```bash
sudo nano /etc/mqvpn/server.conf
sudo systemctl restart mqvpn-server
```

ポートを 443 以外に変更した場合は、アプリ側プロファイルのポート番号と firewall 設定も同じ値に変更します。

## アンインストール

mqvpn を削除する場合は、公式 install script の `--uninstall` を使います。

```bash
curl -fsSL https://github.com/mp0rta/mqvpn/releases/latest/download/install.sh \
  | sudo bash -s -- --uninstall
```

削除後に service が残っていないか確認します。

```bash
systemctl status mqvpn-server --no-pager
```

設定や証明書を手元に残したくない場合は、内容を確認してから `/etc/mqvpn` を削除します。

```bash
sudo rm -rf /etc/mqvpn
```

UFW の許可ルールも不要なら削除します。

```bash
sudo ufw delete allow 443/udp
sudo ufw reload
```

## セキュリティ上の注意

- UDP 443 は必要な接続元からのみ到達できるよう、VPS 側 firewall で制限できる場合は制限してください。
- 自己署名証明書は検証を緩めるため、公開運用では Let's Encrypt などの信頼済み証明書へ置き換えることを推奨します。
- mqvpn の control API は認証なしのため、有効化する場合は `127.0.0.1` bind と firewall / SSH tunnel で保護してください。
- systemd service の実行ユーザーや capability を変更する場合は、TUN 作成、ルーティング、低番ポートの bind に必要な権限が残っているか確認してください。

## 開発者向け構成

mqvpn との接続部分は、Android アプリ側の設定変換と VPN service 実装に分けています。mqvpn 本体や SDK を更新する場合は、この境界を保つとアプリ側 UI への影響を抑えられます。

| ファイル | 役割 |
| --- | --- |
| `app/src/main/java/com/example/glorytun/MqvpnConfigFactory.kt` | アプリの接続プロファイルから mqvpn config を生成 |
| `app/src/main/java/com/example/glorytun/MqvpnBondingService.kt` | mqvpn VPN service と既存 UI への状態通知を接続 |
| `app/src/main/java/com/mqvpn/sdk/` | mqvpn Android SDK source |
| `app/src/main/jniLibs/arm64-v8a/libmqvpn_jni.so` | mqvpn native bridge |
| `app/src/main/java/com/example/glorytun/AppUpdateManager.kt` | GitHub Releases を参照するアプリ内 updater |

## リリース

公開する APK は GitHub Releases に添付します。アプリ内 updater は GitHub Releases の最新 release と APK asset を参照します。

リリース時は `app/build.gradle` の `versionCode` / `versionName` を更新し、semantic versioning に沿った tag と release 名を使ってください。
