# PROJECT_INDEX.md — BondVPN Android

## 概要

BondVPN は Android `VpnService` 上で mqvpn を動かす VPN アプリ。glorytun 依存の C/JNI/libsodium ツリーは削除し、mqvpn Android SDK source と公式 v0.7.0 由来の `libmqvpn_jni.so` を使う構成に変更した。

## 重要な設計境界

| 境界 | ファイル | 目的 |
| --- | --- | --- |
| app → mqvpn config | `app/src/main/java/com/example/glorytun/MqvpnConfigFactory.kt` | プロファイル設定を `MqvpnConfig` に変換する |
| VPN service | `app/src/main/java/com/example/glorytun/MqvpnBondingService.kt` | `MqvpnVpnService` を継承し、TUN 作成、通知、状態 broadcast、統計 broadcast を担当する |
| mqvpn SDK | `app/src/main/java/com/mqvpn/sdk/` | 公式 mqvpn Android SDK source。更新時はこの subtree を差し替える |
| mqvpn native | `app/src/main/jniLibs/arm64-v8a/libmqvpn_jni.so` | 公式 mqvpn sample APK 由来の native bridge |

UI から `com.mqvpn.sdk.*` を直接広く参照しない。mqvpn の API 変更が来た場合は `MqvpnConfigFactory` / `MqvpnBondingService` / SDK subtree の差し替えで収める。

## 接続フロー

```text
DashboardFragment / BondingFragment
  -> ConnectionController.startVpn()
  -> MqvpnBondingService ACTION_CONNECT
  -> MqvpnConfigFactory.fromIntent()
  -> MqvpnVpnService.startTunnel()
  -> mqvpn SDK NetworkMonitor / PathBinder
  -> libmqvpn_jni.so
  -> MqvpnBondingService broadcast
  -> MainActivity / VpnViewModel
```

## 旧 glorytun から削除したもの

- `app/src/main/cpp/`
- `glorytun_jni.c`
- vendored glorytun source
- vendored libsodium headers / static library
- glorytun 専用 path 追加 JNI
- glorytun 専用 native rate control
- `GlorytunVpnService.kt`
- `TrafficStatsManager.kt`
- `BandwidthController.kt`

## ビルド要点

| 項目 | 値 |
| --- | --- |
| applicationId | `com.example.glorytun` |
| minSdk | 26 |
| targetSdk | 34 |
| ABI | `arm64-v8a` |
| version | `1.4` |

`applicationId` は既存インストールと updater 継続のため維持している。ユーザー表示は BondVPN / mqvpn に更新済み。

## リリース運用

このアプリは GitHub Releases updater を持つため、変更後は GitHub push だけで終わらせない。

1. `versionCode` / `versionName` を上げる。
2. `gradle test assembleDebug` を通す。
3. commit / push する。
4. semantic versioning の tag を作る。
5. GitHub Release を作成または更新し、APK asset を公開する。
