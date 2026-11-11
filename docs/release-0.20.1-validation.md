# PdfReader 0.20.1 ARM64 验收记录

日期：2026-11-11（北京时间）。用户明确仅需要 ARM64，设备验收仅使用 POCO F2 Pro（`31936bb6`），不运行模拟器。

## 变更

- 版本 `0.20.1`，versionCode `30`，正式包名继续使用 `com.loooong.pdfreader`。
- Gradle `defaultConfig.ndk.abiFilters` 仅包含 `arm64-v8a`，适用于 Debug 和 Release。
- 移除 `armeabi-v7a`、`x86`、`x86_64` 的重复原生库；完整保留 ARM64 离线 OCR、PDF 查看编辑、模型、中文字体和文档签名功能。
- 使用 0.20.0 的同一正式签名，可在 ARM64 设备覆盖升级；其他架构不再提供安装包。
- APK 沿用 `PdfReader-0.20.1.apk` 命名，与现有在线更新资产选择规则一致。

## 验证方式

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/release.py build
adb -s 31936bb6 shell am instrument -w com.loooong.pdfreader.debug.test/androidx.test.runner.AndroidJUnitRunner
```

验证安装包 ABI 列表、ARM64 原生库与模型/字体逐项一致性、正式签名、覆盖升级；POCO 运行完整设备回归，并增加真实 ML Kit 位图识别测试，确认原生识别库和离线模型可用。

本轮不调整原生库压缩方式、加密库资源、PDFBox 保留规则或字体覆盖范围。

## 已验证结果

- APK 仅包含 `arm64-v8a`，ARM64 原生库、字体与 OCR 模型等 201 项业务资源与 0.20.0 的内容哈希相同。
- 0.20.0：66,934,240 字节；0.20.1：36,897,313 字节；减少 30,036,927 字节（44.88%）。
- 发布包 SHA256：`ada3966650faf85cf67bed907d0a431adcfb658fb52bfc23dff7b0c410a4621f`。
- 证书 SHA256：`dba699de81201f840f0ec15d7fbc68ae8c3dffee20fe7647dddac9d8d7902eed`，与 0.20.0 一致。
- Release 单测：234 项中 233 通过、1 个既有跳过项、0 失败。
- Release Lint：0 错误、48 警告、6 提示；R8、资源压缩及正式签名构建通过。
- POCO F2 Pro：18 项设备回归全部通过，新增真实 OCR 测试成功识别位图中的 PDF 与 2026。
- 正式包已在 POCO 上覆盖升级并成功启动，版本 0.20.1、versionCode 30。
- 正式版界面实测：打开仅含图片的 PDF → OCR → 另存为；独立 pypdf 回读确认可提取 `PDF READER` 和 `2026`，导出文件保存在本机 `app/build/acceptance/0.20.1/`。
