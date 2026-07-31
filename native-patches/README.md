# mqvpn ネイティブ差分

`app/src/main/jniLibs/arm64-v8a/libmqvpn_jni.so` は、mqvpn v0.14.1
（commit `48ed99d2ad66f47de86e032cf7705337c67469da`）へ
`mqvpn-v0.14.1-path-rate-limit.patch` を適用してビルドしています。

この差分は経路ごとの送信レート上限APIを追加し、Androidカーネルの
キュー方式に依存せず上り速度を制限するためのものです。下り方向は
Kotlin側の `PathRateLimiter` が制御します。

ビルド条件:

- Android NDK 27
- API level 26
- arm64-v8a
- mqvpn公式の `scripts/build_android.sh`
- `:sdk-native:assembleDebug` のstrip済み `libmqvpn_jni.so`
