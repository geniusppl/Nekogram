# ⚡ Nekogram 智能加速下载版 (Smart Boost)

> **本分支由 AI（Hark）完成开发与构建，不是 Nekogram 官方版本。**
> This branch was developed and built by AI (Hark). It is not an official Nekogram release.

## 做了什么 / What's different

- **智能加速下载**：根据实时网速自动调整并发（512KB 分块，同时下载 6–16MB，8 条连接），网速好时加大、遇到限速时平稳回落，下载和看视频比官方更快。
- 与官方 Nekogram 共存（包名 `tw.nekomimi.nekogram.smart`），仅 arm64-v8a，无 FCM 推送。
- **Smart download boost**: adapts download parallelism to measured throughput (512KB parts, 6–16MB in flight, 8 connections).

## 安全 / Security

- 固定发布签名，证书 SHA-256：
  `8D:9E:07:5F:72:AC:7C:FE:2E:1B:16:63:7A:B6:11:8E:36:E3:BA:DA:70:A0:6A:53:F6:82:7B:7A:43:56:87:3F`
- 启动时校验签名，被改包的 APK 无法运行；关闭系统备份；构建脚本的 Actions 固定到具体提交。
- 只从本仓库 Releases 下载安装。

---

# 🐾 Nekogram
[![Crowdin](https://badges.crowdin.net/e/a094217ac83905ae1625526d59bba8dc/localized.svg)](https://neko.crowdin.com/nekogram)  
Nekogram is a third-party Telegram client with not many but useful modifications.

- Website: https://nekogram.app
- Telegram channel: https://t.me/nekoupdates
- Downloads: https://nekogram.app/download
- Feedback: https://github.com/Nekogram/Nekogram/issues

## API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTProto protocol manuals: https://core.telegram.org/mtproto

## Compilation Guide

1. Download the Nekogram source code ( `git clone --recursive --shallow-submodules https://github.com/Nekogram/Nekogram.git` )
1. Fill out storeFile, storePassword, keyAlias, keyPassword in local.properties to access your release.keystore
1. Go to https://console.firebase.google.com/, create two android apps with application IDs tw.nekomimi.nekogram and tw.nekomimi.nekogram.beta, turn on firebase messaging and download `google-services.json`, which should be copied into `TMessagesProj` folder.
1. Open the project in the Studio (note that it should be opened, NOT imported).
1. Fill out values in `TMessagesProj/src/main/java/tw/nekomimi/nekogram/Extra.java` – there’s a link for each of the variables showing where and which data to obtain.
1. You are ready to compile Nekogram.

## Localization

Nekogram is forked from Telegram, thus most locales follows the translations of Telegram for Android, checkout https://translations.telegram.org/en/android/.

As for the Nekogram specialized strings, we use Crowdin to translate Nekogram. Join project at https://neko.crowdin.com/nekogram. Help us bring Nekogram to the world!
