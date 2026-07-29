# ApkSign

一个简单的 Android 端 APK 签名工具，基于 Google 官方 [apksig](https://android.googlesource.com/platform/tools/apksig/) 库实现。

## 功能

- **APK 签名**：支持 v1 / v2 / v3 签名方案，可自由勾选组合
- **密钥管理**：导入 JKS / BKS / PKCS12 密钥库，或直接在应用内生成新密钥，支持导出
- **快速签名**：从其他应用分享 APK 过来时，弹出简洁对话框直接完成签名，无需进入主界面
- **签名后安装**：签名完成后可选择系统中任意安装器直接安装产物

## 输出位置

- 主界面签名：优先存回原 APK 所在目录（需授予"所有文件访问权限"），否则存到 `Download/ApkSign/`
- 分享快速签名：固定存到 `Download/ApkSign/原文件名-signed.apk`

## 构建

环境要求：JDK 21、Android SDK 36。

```bash
./gradlew :app:assembleDebug
```

- 语言 / 构建：Java + Gradle Kotlin DSL（AGP 9.x）
- minSdk 29（Android 10），targetSdk 36
- 版本号由 Git 提交数与短哈希自动生成
- 正式签名可在 `gradle.properties` 中配置 `storeFile` / `storePassword` / `keyAlias` / `keyPassword`

## 主要依赖

- [apksig](https://android.googlesource.com/platform/tools/apksig/) —— APK 签名核心
- [Bouncy Castle](https://www.bouncycastle.org/) —— 密钥库解析与证书生成
- Material Components —— Material 3 界面，Android 12+ 支持动态取色


> 若涉及到侵权问题，请联系我