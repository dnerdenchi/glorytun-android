# BondVPN Android

BondVPN は Android の `VpnService` 上で [mqvpn](https://github.com/mp0rta/mqvpn) を動かす VPN アプリです。mqvpn は MASQUE CONNECT-IP と Multipath QUIC を使うため、Wi-Fi / モバイル回線など複数経路を 1 本の VPN トンネルとして扱えます。

## 現在の構成

| 項目 | 内容 |
| --- | --- |
| VPN エンジン | mqvpn v0.7.0 |
| ボンディング方式 | mqvpn の Multipath QUIC / WLB scheduler |
| Android 側 | Kotlin + mqvpn Android SDK source |
| Native 側 | 公式 mqvpn sample APK 由来の `libmqvpn_jni.so` |
| 対応 ABI | `arm64-v8a` |
| 最小 Android | API 26 |
| アプリ更新 | GitHub Releases の APK asset をアプリ内 updater が参照 |

glorytun 時代の CMake/JNI/libsodium/glorytun ソースは削除済みです。アプリ固有の mqvpn 依存は主に次の 2 箇所へ寄せています。

- `MqvpnConfigFactory.kt`: UI のプロファイル設定を mqvpn の `MqvpnConfig` へ変換する。
- `MqvpnBondingService.kt`: `MqvpnVpnService` を継承し、状態通知と通信統計を既存 UI 用 broadcast へ変換する。

mqvpn 本体の更新が入った場合は、この境界を保ったまま `com.mqvpn.sdk.*` と `libmqvpn_jni.so` を更新してください。

## Android ビルド

この環境では Gradle wrapper 実体が無くても、キャッシュ済み Gradle と Android Studio JBR でビルドできます。

```powershell
$env:JAVA_HOME="D:\Soft\AndroidStudio\jbr"
gradle assembleDebug
```

生成物:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## アプリ側プロファイル

接続タブで次を登録します。

| 入力 | 説明 |
| --- | --- |
| サーバーアドレス | mqvpn サーバーの IP またはホスト名 |
| ポート番号 | 通常は `443` |
| mqvpn 認証キー | サーバーの `/etc/mqvpn/server.conf` にある `[Auth] Key` |
| 自己署名証明書を許可する | install script で作った自己署名証明書のサーバーへ接続する場合はオン |

本番運用で Let's Encrypt など信頼済み証明書を使う場合は、「自己署名証明書を許可する」をオフにします。

## mqvpn サーバーを建てる方法

Ubuntu / Debian 系の VPS を例にします。サーバー側は UDP 443 を使います。

### 1. OS を更新する

```bash
sudo apt update
sudo apt upgrade -y
```

### 2. mqvpn をインストールして起動する

公式 install script で最新 release を入れます。

```bash
curl -fsSL https://github.com/mp0rta/mqvpn/releases/latest/download/install.sh \
  | sudo bash -s -- --start --port 443 --subnet 10.8.0.0/24
```

この手順では `/etc/mqvpn/server.conf`、自己署名 TLS 証明書、認証キーが作られ、`mqvpn-server` systemd service が起動します。

### 3. UDP 443 を開ける

UFW を使っている場合:

```bash
sudo ufw allow 443/udp
sudo ufw reload
```

クラウド側の security group / firewall でも UDP 443 を許可してください。

### 4. 認証キーを確認する

```bash
sudo grep -A3 '^\[Auth\]' /etc/mqvpn/server.conf
```

`Key = ...` の値を Android アプリの「mqvpn 認証キー」に入れます。

### 5. サービス状態を確認する

```bash
sudo systemctl status mqvpn-server
sudo journalctl -u mqvpn-server -f
```

### 6. Android から接続する

アプリでプロファイルを作成します。

- サーバーアドレス: VPS のグローバル IP または DNS 名
- ポート番号: `443`
- mqvpn 認証キー: `/etc/mqvpn/server.conf` の `[Auth] Key`
- 自己署名証明書を許可する: install script 標準の自己署名証明書ならオン

## 本番向けの注意

- 自己署名証明書は検証を緩めるため、公開運用では Let's Encrypt などの信頼済み証明書へ置き換えることを推奨します。
- mqvpn の control API は認証なしのため、有効化する場合は `127.0.0.1` bind と firewall / SSH tunnel で保護してください。
- 複数ユーザーを使う場合は server config の `[Auth] User = name:key` 形式を使うと、サーバー側メトリクスをユーザー単位で分けやすくなります。

## mqvpn を更新する時の作業メモ

1. 公式 release を確認し、SDK source と `libmqvpn_jni.so` の互換性を確認する。
2. `app/src/main/java/com/mqvpn/sdk/` を新しい mqvpn Android SDK source に更新する。
3. 公式 sample APK または自前ビルドから `app/src/main/jniLibs/arm64-v8a/libmqvpn_jni.so` を更新する。
4. `MqvpnConfigFactory.kt` と `MqvpnBondingService.kt` の compile error だけを直す。UI 側へ mqvpn API を直接広げない。
5. `app/build.gradle` の `versionCode` / `versionName` を semantic versioning に沿って上げる。
6. `gradle test assembleDebug` を実行する。
7. GitHub に push し、同じ version の GitHub Release に APK asset を公開する。

## 主なファイル

| ファイル | 役割 |
| --- | --- |
| `app/src/main/java/com/example/glorytun/MqvpnBondingService.kt` | mqvpn VPN service 実装 |
| `app/src/main/java/com/example/glorytun/MqvpnConfigFactory.kt` | app 設定から mqvpn config への変換 |
| `app/src/main/java/com/mqvpn/sdk/` | mqvpn Android SDK source |
| `app/src/main/jniLibs/arm64-v8a/libmqvpn_jni.so` | mqvpn native bridge |
| `app/src/main/java/com/example/glorytun/AppUpdateManager.kt` | GitHub Releases updater |
