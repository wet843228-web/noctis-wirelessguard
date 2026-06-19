# NOCTIS WirelessGuard

Android 14 / Xperia 5 IV 向け無線状態監査APK

## セキュリティ設計

### 「絶対共有できない」実装

| 保護層 | 実装 | 効果 |
|--------|------|------|
| `FLAG_SECURE` | `MainActivity.onCreate()` | スクリーンショット不可・画面共有黒画面・録画黒画面・Recentsサムネイル黒 |
| `allowBackup="false"` | AndroidManifest | Google Driveバックアップ無効 |
| `dataExtractionRules` | data_extraction_rules.xml | USB転送時のデータ抽出無効 |
| `fullBackupContent` | backup_rules.xml | 全ドメインのバックアップ除外 |
| ProGuard | `minifyEnabled true` | リリースビルドのコード難読化 |

### 監視対象イベント

- `BT_STATE` : Bluetooth ON/OFF
- `BT_SCAN_MODE` : **DISCOVERABLE検知（Quick Share/AirDrop露出）**
- `WIFI_STATE` : Wi-Fi ON/OFF
- `P2P_STATE` : **Wi-Fi P2P 有効化検知（Quick Share基盤）**
- `P2P_PEERS` : **P2Pピア出現（周辺Quick Share端末）**
- `P2P_CONNECTED` : **P2P接続確立（要警戒）**
- `LOCATION` : 位置情報プロバイダ変化

### IPCログ出力

```
/sdcard/guardian/wireless_events.jsonl
```

Termux側の `guardian_wireless_audit.sh` はこのファイルをtailして読む。

例:
```json
{"ts":"2026-06-19T06:30:01Z","event":"BT_SCAN_MODE","message":"BT SCAN_MODE: DISCOVERABLE","source":"WirelessGuard"}
{"ts":"2026-06-19T06:30:05Z","event":"P2P_PEERS","message":"⚠️ P2Pピア変化: 2台検知","source":"WirelessGuard"}
```

## ビルド方法

### GitHub Actions（推奨）

```bash
# wet843228-web アカウントの noctis-wirelessguard リポジトリへプッシュ
git init
git remote add origin https://github.com/wet843228-web/noctis-wirelessguard.git
git add .
git commit -m "initial: WirelessGuard v1.0"
git push -u origin main
```

Actions が自動実行され `WirelessGuard-debug.apk` がArtifactに保存される。

### 署名付きリリースビルド（オプション）

GitHub Secrets に以下を登録：
- `KEYSTORE_BASE64` : keystoreをbase64エンコードしたもの
- `KEY_ALIAS`
- `KEY_PASSWORD`
- `STORE_PASSWORD`

## インストール

```bash
# Termuxから
adb install -r app-debug.apk

# または直接ファイルマネージャから
```

## Termux IPC連携

```bash
# wireless_events.jsonl をリアルタイム監視
tail -f /sdcard/guardian/wireless_events.jsonl | while read line; do
    EVENT=$(echo "$line" | python3 -c "import sys,json; d=json.loads(sys.stdin.read()); print(d.get('event',''))")
    MSG=$(echo "$line"   | python3 -c "import sys,json; d=json.loads(sys.stdin.read()); print(d.get('message',''))")
    if [[ "$EVENT" == P2P* ]] || [[ "$EVENT" == *DISCOVERABLE* ]]; then
        termux-notification --title "⚠️ NOCTIS ALERT" --content "$MSG"
    fi
done
```
