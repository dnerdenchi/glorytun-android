# PairBond リレー

PairBond は、複数のペアSIM端末を同時に使う BondVPN のプロキシ専用集約リレーです。mqvpn の UDP 443 と競合しない TCP 443 を待ち受けます。

## 動作

- 受信端末はペア端末ごとに、Wi-Fi LAN 上の暗号化チャネルを作ります。
- ペア端末は、そのチャネルを受けて自端末のモバイル回線に固定した TCP 接続をこのリレーへ張ります。
- 受信端末とリレーの間は、mqvpn の [Auth] Key から導出した AES-GCM で暗号化されます。ペア端末は宛先やプロキシ通信の中身を読めません。
- TCP はオフセット付きチャンクに分割し、複数パスへスケジューリングし、リレーと受信端末で順序復元します。ACK が来ないチャンクは別パスを優先して再送します。
- 自動ボンディング、バックアップ専用、使用しない の優先度を受け取り、通常時はバックアップパスへデータを流しません。
- SOCKS5 の TCP CONNECT、HTTP CONNECT、SOCKS5 UDP ASSOCIATE を扱います。

## 配備

mqvpn サーバー上で、リポジトリのこのフォルダーを任意の一時ディレクトリへコピーしてから実行します。

~~~bash
cd /path/to/pairbond-relay
sudo ./install.sh
sudo ufw allow 443/tcp
sudo systemctl status pairbond-relay --no-pager
sudo ss -ltnp | grep ':443'
~~~

pairbond-relay は mqvpn ユーザーで実行され、/etc/mqvpn/server.conf の [Auth] Key だけを読みます。認証キーを別ファイルへ複製したり、ログへ出力したりしません。

## 確認

~~~bash
sudo journalctl -u pairbond-relay -n 100 --no-pager
sudo systemctl is-active mqvpn-server pairbond-relay
~~~

Android 側では、各ペア端末で共有を許可し、受信端末の Pair & Share 画面で2台以上を自動ボンディングに設定してから、ダッシュボードでプロキシ接続します。

## セキュリティ境界

- リレーはプライベート・ループバック・リンクローカル・予約アドレス宛ての TCP/UDP 接続を拒否します。
- PairBond の認証に失敗した接続は、暗号化フレームを読む前に切断します。
- フレーム、順序待ち、未ACK下りデータのサイズに上限を設けています。
- 外部公開するのは TCP 443 のみです。管理ポートや HTTP API はありません。
