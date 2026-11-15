# PdfReader 0.20.3 更新检查验收记录

日期：2026-11-15（北京时间）。本次按用户指示仅操作 Redmi Note 8 Pro（`wsvwypiz7xwslvl7`，API 36），不操作 POCO F2 Pro，不运行模拟器。

## 变更

- 版本 `0.20.3`，versionCode `32`，正式包名 `com.loooong.pdfreader`，仅包含 `arm64-v8a`。
- 移除应用内 `DownloadManager` 下载、APK SHA256/签名校验、安装权限检查、`PackageInstaller` 会话和安装广播接收器。
- 设置页保留 GitHub Release 版本检查：有新版显示版本说明并打开 GitHub 发布页；无新版显示“已是最新版本”。
- 版本检查只读取 `tag_name`、`name`、`body` 和 `html_url`，不再解析 APK 下载资产。

## 构建与制品

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) python3 scripts/release.py build
```

- Release 单元测试：230 项，229 通过、1 个既有跳过项，0 失败、0 错误。
- Release Lint：0 错误、43 警告、6 提示。
- R8、资源压缩及正式签名构建通过。
- APK：`PdfReader-0.20.3.apk`，36,980,138 字节（约 36.98 MB）。
- APK SHA256：`beeebf4b594d6dd58283ed580e9fb38f99344e894eefd3d146ce7348697e3efa`。
- 签名证书 SHA256：`dba699de81201f840f0ec15d7fbc68ae8c3dffee20fe7647dddac9d8d7902eed`，与前版一致。
- `apksigner verify` 通过；`aapt` 确认包名、版本码 32、版本名 0.20.3、最低 API 26 和仅 ARM64。
- 201 项原生库及业务 assets 与 0.20.2 逐项 SHA256 相同。

## Redmi Note 8 Pro 实机验证

1. 安装正式版 0.20.2，回读版本码 31 并冷启动成功。
2. 使用 `adb -s wsvwypiz7xwslvl7 install -r` 覆盖安装正式版 0.20.3，安装成功。
3. 冷启动返回 `Status: ok`，设备回读版本码 32、版本名 0.20.3。
4. 进入“设置 → 检查更新”，实际访问 GitHub Release 后显示“已是最新版本 · v0.20.3”。
5. 调试 APK 权限清单仅保留网络及系统生成的动态广播/网络状态权限，没有 `REQUEST_INSTALL_PACKAGES`；源码中无 `DownloadManager`、`PackageInstaller` 或应用内 APK 安装入口。

本机构建日志、制品核验 JSON、SHA256SUMS 与 Redmi 截图保存在 `app/build/release-0.20.3/`。
