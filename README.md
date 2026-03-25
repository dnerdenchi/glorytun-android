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

## 最近の更新

- **VPN接続のフルトンネル化**:
  `GlorytunVpnService.kt` にて、`addRoute("0.0.0.0", 0)` および `addRoute("::", 0)` を設定し、すべてのトラフィックがVPNを経由するように変更しました。また、DNSサーバーとして `8.8.8.8` (Google DNS) と `1.1.1.1` (Cloudflare) を追加設定しました。
- **ルーティングループ防止**:
  JNI層の `gt_socket` フックにより `VpnService.protect(fd)` を呼び出し、VPN自身のパケットがVPNインターフェースに再注入されない（ルーティングループが発生しない）ように対応済みです。
