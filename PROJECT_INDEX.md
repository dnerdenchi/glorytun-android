# PROJECT_INDEX.md — glorytun-android

作成日: 2026-03-27

---

## プロジェクト概要

Android 向けの VPN アプリ。glorytun (マルチパス UDP VPN トンネル) を Android 上で動作させるため、JNI を介して C ライブラリを呼び出す構成をとる。

- 言語: Kotlin (Android 側) + C (glorytun/mud コア)
- ビルドシステム: Gradle + CMake (NDK)
- 対象 ABI: arm64-v8a のみ
- 最小 SDK: 24 (Android 7.0)
- ターゲット SDK: 34 (Android 14)
- パッケージ名: `com.example.glorytun`

### 主な機能

| 機能 | 状態 |
|------|------|
| glorytun による暗号化 VPN トンネル | 実装済み (Phase 1) |
| WiFi と SIM カードの同時通信 (マルチパス) | 実装済み (Phase 2) |
| libsodium による暗号化 (aegis256 / ChaCha20-Poly1305) | 実装済み |
| 接続情報の永続化 (EncryptedSharedPreferences) | 実装済み |
| フォアグラウンドサービス通知 | 実装済み |

---

## ディレクトリ構造

```
glorytun-android/
├── build.gradle                        # ルートプロジェクト Gradle 設定
├── settings.gradle                     # モジュール構成・リポジトリ設定
├── gradle.properties
├── local.properties
├── README.md
└── app/
    ├── build.gradle                    # アプリモジュール Gradle 設定 (CMake 連携含む)
    └── src/main/
        ├── AndroidManifest.xml         # 権限・コンポーネント宣言
        ├── java/com/example/glorytun/
        │   ├── MainActivity.kt         # UI エントリポイント
        │   └── GlorytunVpnService.kt   # VPN サービス + JNI 呼び出し
        ├── res/
        │   ├── layout/activity_main.xml
        │   └── values/strings.xml
        └── cpp/
            ├── CMakeLists.txt          # NDK ビルド定義
            ├── glorytun_jni.c          # JNI ブリッジ (メインエントリ)
            ├── include/
            │   ├── sodium.h            # libsodium ヘッダ (一式)
            │   └── sodium/             # libsodium サブヘッダ群
            ├── libs/
            │   └── arm64-v8a/
            │       └── libsodium.a     # 静的ライブラリ (事前ビルド)
            └── glorytun/               # glorytun ソースコード (git submodule)
                ├── src/
                │   ├── main.c          # glorytun_main() エントリポイント
                │   ├── bind.c          # VPN トンネルメインループ + g_mud グローバル
                │   ├── common.c/h      # 共通ユーティリティ・シグナル変数
                │   ├── tun.c/h         # TUN デバイス操作 (Android では fd を再利用)
                │   ├── ctl.c/h         # UNIX ソケット制御インターフェース
                │   ├── iface.c/h       # ネットワークインターフェース操作
                │   ├── ip.h            # IP パケット検証
                │   ├── path.c          # パス管理 CLI コマンド
                │   ├── set.c           # glorytun set コマンド
                │   ├── show.c          # glorytun show コマンド
                │   ├── list.c          # glorytun list コマンド
                │   ├── bench.c         # 暗号ベンチマーク
                │   ├── keygen.c        # 鍵生成
                │   ├── version.c       # バージョン表示
                │   └── argz.h          # コマンドライン引数パーサ (glorytun 独自)
                ├── mud/
                │   ├── mud.c           # mud コア (マルチパス UDP, 暗号化, 輻輳制御)
                │   ├── mud.h           # mud 公開 API + Android 拡張 API
                │   └── aegis256/
                │       ├── aegis256.c  # AEGIS-256 暗号実装 (ARM Crypto 拡張使用)
                │       └── aegis256.h
                └── argz/
                    ├── argz.c          # CLI 引数パーサ実装
                    └── argz.h
```

---

## エントリポイント

### Android アプリ起動フロー

```
MainActivity (onCreate)
  └─ ユーザーが Connect ボタンを押す
       └─ VpnService.prepare() → パーミッション確認
            └─ startVpnConnection()
                 └─ GlorytunVpnService.startService(ACTION_CONNECT)
                      └─ connectVpn()  [別スレッド]
                           ├─ VpnService.Builder.establish() → TUN fd 取得
                           ├─ startGlorytunNative(fd, ip, port, secret)  [JNI]
                           │    └─ glorytun_thread → glorytun_main → gt_bind
                           └─ waitForGlorytunReady() → registerNetworkCallbacks()
```

### ネイティブ側起動フロー

```
glorytun_main()  [src/main.c]
  └─ gt_bind()   [src/bind.c]
       ├─ sodium_init()
       ├─ mud_create()  → g_mud にセット
       ├─ tun_create()  → android_tun_fd を返す (VpnService.Builder.establish() の fd)
       ├─ mud_set_path() → 初期パス作成
       └─ select() ループ (tun ↔ mud 間のパケット転送)
```

---

## 主要モジュールと役割

### Kotlin (app/src/main/java/com/example/glorytun/)

| ファイル | 役割 |
|----------|------|
| `MainActivity.kt` | UI。サーバー IP・ポート・シークレットキーの入力フォーム。`EncryptedSharedPreferences` で設定を永続化。VPN 接続/切断の制御。`BroadcastReceiver` で VPN 状態を受信して表示を更新する。 |
| `GlorytunVpnService.kt` | `VpnService` を継承したフォアグラウンドサービス。TUN インターフェースの作成、JNI 呼び出し、マルチパス用ネットワークコールバック管理 (WiFi / Cellular 別インスタンス)、`bindSocketToNetwork()` によるソケットのネットワーク紐付けを担う。 |

### C/JNI (app/src/main/cpp/)

| ファイル | 役割 |
|----------|------|
| `glorytun_jni.c` | JNI ブリッジ本体。Kotlin からの呼び出しを受け付け、glorytun メインループを別 pthread で起動する。`protect_socket_from_c()` で VPN ルーティングループを防止。マルチパス用の `addPathForNetwork` / `removePathForNetwork` JNI メソッドを実装。 |
| `glorytun/src/main.c` | `glorytun_main()` を定義。引数パーサで `gt_bind` 等のサブコマンドにディスパッチする。シグナルハンドラのセットアップも行う。 |
| `glorytun/src/bind.c` | VPN のメインループ。`mud_create()` で mud を生成し `g_mud` グローバルへセット。`select()` で tun fd と mud fd を監視し、パケットを双方向転送する。Android では MTU 再設定をスキップ。 |
| `glorytun/src/tun.c` | TUN デバイスの作成・読み書き。Android では `android_tun_fd` が設定されていれば `/dev/net/tun` を開かずにその fd を再利用する。 |
| `glorytun/src/common.c/h` | ログ、hex 変換、グローバルシグナル変数 (`gt_quit`, `gt_reload`, `gt_alarm`) を定義。Android 向けに `socket()` を `gt_socket()` にマクロ置換するヘッダも含む。 |
| `glorytun/src/ctl.c/h` | UNIX ドメインソケット経由の制御インターフェース (path の追加/変更/取得など)。 |
| `glorytun/src/iface.c/h` | ネットワークインターフェースの MTU 設定など OS レベルの操作。Android ではスキップされる。 |
| `glorytun/mud/mud.c` | mud ライブラリのコア。マルチパス UDP 管理、AEGIS-256 / ChaCha20-Poly1305 による暗号化、RTT 計測、輻輳制御、MTU プロービングを実装。Android 向けに epoll を使用したパス別ソケット管理機能を追加。 |
| `glorytun/mud/mud.h` | mud の公開 API。Android ビルド時に `socket()` を `gt_socket()` に置換するマクロと、Android 専用の `mud_set_path_socket()` / `mud_clear_path_socket()` を定義。 |
| `glorytun/mud/aegis256/aegis256.c` | AEGIS-256 暗号実装。ARM Crypto 拡張 (`-march=armv8-a+crypto`) でコンパイルされ、AES ハードウェア命令を利用。 |
| `glorytun/argz/argz.c` | glorytun 独自の CLI 引数パーサ。 |
| `glorytun/src/keygen.c` | 32 バイト鍵の生成と 64 文字 hex への変換。 |

---

## JNI ブリッジ API サーフェス

### Kotlin → C (外部関数宣言, GlorytunVpnService.kt)

| Kotlin 宣言 | 対応する C 関数名 | 説明 |
|-------------|-------------------|------|
| `startGlorytunNative(fd: Int, ip: String, port: String, secret: String): Int` | `Java_com_example_glorytun_GlorytunVpnService_startGlorytunNative` | glorytun を別スレッドで起動する。キーファイルをキャッシュ領域に書き出し、`pthread` で `glorytun_main` を実行する。 |
| `stopGlorytunNative()` | `Java_com_example_glorytun_GlorytunVpnService_stopGlorytunNative` | `gt_quit = 1` をセットしてメインループを終了させる。 |
| `isGlorytunReady(): Boolean` | `Java_com_example_glorytun_GlorytunVpnService_isGlorytunReady` | `g_mud != NULL` かどうかを返す。mud 初期化完了のポーリングに使用。 |
| `addPathForNetwork(localIp: String, networkHandle: Long): Int` | `Java_com_example_glorytun_GlorytunVpnService_addPathForNetwork` | 新ネットワーク (WiFi/SIM) が使用可能になった際、専用 UDP ソケットを作成して mud に追加する。 |
| `removePathForNetwork(localIp: String): Int` | `Java_com_example_glorytun_GlorytunVpnService_removePathForNetwork` | ネットワーク消滅時に mud からパスを削除しソケットをクローズする。 |

### C → Kotlin (コールバック, JNI 経由)

| C 関数 | 呼び出す Kotlin メソッド | 説明 |
|--------|--------------------------|------|
| `protect_socket_from_c(fd)` | `VpnService.protect(fd)` | ソケットを VPN トンネルの外側に配置し、ルーティングループを防止する。 |
| `bind_socket_to_network(fd, networkHandle)` | `GlorytunVpnService.bindSocketToNetwork(fd, networkHandle)` | ソケットを特定の `Network` オブジェクトに紐付ける (マルチパス用)。 |

### mud Android 拡張 API (mud.h)

| 関数 | 説明 |
|------|------|
| `mud_set_path_socket(mud, local_addr, fd)` | パスに専用ソケットを設定する。SO_REUSEADDR / IP_PKTINFO を設定し、epoll に登録する。 |
| `mud_clear_path_socket(mud, local_addr)` | パスの専用ソケットを解除して close する。 |

### グローバル変数 (C 側)

| 変数 | 型 | 定義場所 | 説明 |
|------|----|----------|------|
| `g_mud` | `struct mud *` | `glorytun/src/bind.c` | mud インスタンスへのグローバルポインタ。JNI 側がパス追加に使用。NULL でなければ初期化完了。 |
| `android_tun_fd` | `int` | `glorytun_jni.c` | VpnService.Builder.establish() で得た TUN の fd。tun_create() がこれを返す。 |
| `g_jvm` | `JavaVM *` | `glorytun_jni.c` | JNI_OnLoad で取得した JavaVM。C スレッドから JNI コールバックするために使用。 |
| `g_vpn_service` | `jobject` | `glorytun_jni.c` | GlorytunVpnService の GlobalRef。protect() / bindSocketToNetwork() 呼び出しに使用。 |
| `gt_quit` | `volatile sig_atomic_t` | `glorytun/src/main.c` | 1 にセットするとメインループが終了する。 |

---

## 主要設定ファイル

| ファイル | 説明 |
|----------|------|
| `settings.gradle` | ルートプロジェクト名 ("Glorytun VPN")、モジュール (`:app`)、リポジトリ (google, mavenCentral) の定義。 |
| `build.gradle` (ルート) | AGP 8.13.2、Kotlin 1.9.0 プラグインを宣言。 |
| `app/build.gradle` | compileSdk 34、minSdk 24、ABI `arm64-v8a` のみ、CMake 3.22.1、ViewBinding 有効。依存ライブラリ一覧。 |
| `app/src/main/cpp/CMakeLists.txt` | `glorytun_jni` 共有ライブラリのビルド定義。glorytun/src/*.c と mud/mud.c、aegis256.c を一括コンパイル。libsodium.a をリンク。 |

---

## Android 権限一覧

| 権限 | 用途 |
|------|------|
| `INTERNET` | VPN トンネル用ソケット通信 |
| `ACCESS_NETWORK_STATE` | ネットワーク状態の取得 (ConnectivityManager) |
| `CHANGE_NETWORK_STATE` | requestNetwork() によるネットワーク要求 |
| `FOREGROUND_SERVICE` | フォアグラウンドサービスの起動 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14+ 向けフォアグラウンドサービスタイプ宣言 |
| `BIND_VPN_SERVICE` (サービス権限) | GlorytunVpnService へのバインドを VPN システムのみに制限 |

---

## 依存ライブラリ

### Android / Kotlin

| ライブラリ | バージョン | 用途 |
|------------|-----------|------|
| `androidx.core:core-ktx` | 1.12.0 | Kotlin 拡張 |
| `androidx.appcompat:appcompat` | 1.6.1 | AppCompatActivity |
| `com.google.android.material:material` | 1.11.0 | TextInputLayout 等の Material UI |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | レイアウト |
| `androidx.security:security-crypto` | 1.0.0 | EncryptedSharedPreferences (接続情報の暗号化保存) |

### ネイティブ (C)

| ライブラリ | 種別 | 用途 |
|------------|------|------|
| `libsodium` | 静的ライブラリ (libs/arm64-v8a/libsodium.a) | 暗号化全般 (ChaCha20-Poly1305, AEAD, 鍵交換等) |
| `liblog` (Android NDK) | 動的リンク | `__android_log_print()` によるログ出力 |
| `libc` (Android NDK) | 動的リンク | POSIX 標準ライブラリ |

### ビルドツール

| ツール | バージョン |
|--------|-----------|
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 1.9.0 |
| CMake | 3.22.1 |
| Android NDK | (local.properties 参照) |

---

## マルチパス動作の概要 (Phase 2)

```
GlorytunVpnService
  ├─ wifiCallback  (NetworkCallback)
  │    onAvailable  → addPathForNetwork(wifiLocalIp, wifiHandle)
  │    onLost       → removePathForNetwork(wifiLocalIp)
  └─ cellCallback  (NetworkCallback)
       onAvailable  → addPathForNetwork(cellLocalIp, cellHandle)
       onLost       → removePathForNetwork(cellLocalIp)

addPathForNetwork (JNI, glorytun_jni.c):
  1. socket(SOCK_DGRAM, UDP) で新規ソケット作成
  2. protect_socket_from_c() → VpnService.protect() でルーティングループ防止
  3. bind_socket_to_network() → Network.bindSocket() で特定ネットワークに紐付け
  4. mud_set_path_socket() → mud の内部 epoll に登録

mud (mud.c):
  - 各パスのソケットを epoll で監視
  - RTT・帯域幅に基づいてパスを選択し、パケットを送受信
  - 最大 MUD_PATH_MAX (32) パスをサポート
```

---

## 注意事項・設計上のポイント

- **シグナル競合の回避**: glorytun スレッドは `sigfillset` で全シグナルをブロックする。Android の ANR チェック (SIGQUIT) が glorytun の `gt_quit` フラグを誤って立てるのを防ぐため。
- **mud 初期化待機**: JNI の `startGlorytunNative` はスレッドを起動するだけで戻るため、`isGlorytunReady()` を 50ms 間隔×最大 40 回 (計 2 秒) ポーリングして `g_mud != NULL` を確認してからネットワークコールバックを登録する。
- **TUN fd の共有**: Android では `/dev/net/tun` を開けないため、`android_tun_fd` グローバルを介して `VpnService.Builder.establish()` で取得した fd を `tun_create()` が返す。
- **MTU 設定のスキップ**: Android では `VpnService.Builder.setMtu(1420)` で設定済みのため、mud の MTU 変更イベントに対して `iface_set_mtu()` を呼ばない。
- **キーファイル**: 起動時に `/data/data/com.example.glorytun/cache/keyfile.txt` に 64 文字 hex シークレットを書き出し、glorytun に読み込ませる。`sodium_memzero` でメモリ上のシークレットをゼロ化する。
- **ソケット保護**: mud 内部で `socket()` を呼ぶすべての箇所で `gt_socket()` → `protect_socket_from_c()` → `VpnService.protect()` が自動的に呼ばれる (`mud.h` のマクロ置換による)。
