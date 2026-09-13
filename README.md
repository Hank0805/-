# Mini OBS Android v0.1

Androidだけで画面録画・RTMP配信を行う「OBS風」プロトタイプです。

## v0.1で実装済み

- Android MediaProjectionによる画面キャプチャ
- H.264系ハードウェアエンコード（端末対応範囲）
- MP4録画
- RTMP / RTMPS配信
- 音声ソース切替
  - 内部音声 + マイク
  - 内部音声のみ
  - マイクのみ
- 720p / 1080p
- 30fps / 60fps
- 送信ビットレート表示
- フォアグラウンドサービス対応
- OBS風のシーンUI（v0.1では切替UIのみ。合成処理は未実装）

## 重要な制限

- 内部音声はAndroid 10(API 29)以降で、録音を許可しているアプリの音声だけ取得できます。内部音声だけを選ぶ場合もAndroid仕様上 `RECORD_AUDIO` 権限が必要です。
- DRM保護された映像・音声は取得できません。
- v0.1では「画像・文字・カメラを画面上に重ねて1枚に合成するOBSソース機能」は未実装です。
- 録画ファイルはアプリ専用の Movies/MiniOBS フォルダに保存します。

## 自動APKビルド

GitHub Actionsの `Build Mini OBS APK` が main へのpushごとにDebug APKを生成します。

## ビルド

- JDK 17
- Android SDK 37
- AGP 9.4
- Gradle 9.6

主な依存ライブラリ:
- RootEncoder 2.8.1 (Apache-2.0)
