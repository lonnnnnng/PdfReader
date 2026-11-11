# PdfReader 0.20.2 图标更新验收记录

日期：2026-11-11（北京时间）。本次按用户指示仅操作 Redmi Note 8 Pro（`wsvwypiz7xwslvl7`，API 36），不操作 POCO F2 Pro，不运行模拟器。

## 变更

- 版本 `0.20.2`，versionCode `31`，正式包名 `com.loooong.pdfreader`，仅包含 `arm64-v8a`。
- 使用墨绿底色、折角 PDF 文档与金色编辑笔的新图标，提供方形、圆形、自适应和单色主题图标。
- 更新 README 功能清单、使用边界、构建与设备验证说明；图标源文件和生成方式见 `branding/README.md`。
- PDF 功能代码、离线 OCR 模型、字体和原生库未变，沿用现有发布证书。

## 构建与制品

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) python3 scripts/release.py build
```

- Release 单元测试：234 项，233 通过、1 个既有跳过项，0 失败、0 错误。
- Release Lint：0 错误、46 警告、6 提示，无图标资源警告。
- R8、资源压缩及正式签名构建通过。
- APK：`PdfReader-0.20.2.apk`，36,996,590 字节（37.00 MB），比 0.20.1 增加 99,277 字节。
- APK SHA256：`1523fa27a4dc4a8127c81262716b7df4072918de4a2008321927704d539815e3`。
- 签名证书 SHA256：`dba699de81201f840f0ec15d7fbc68ae8c3dffee20fe7647dddac9d8d7902eed`，与 0.20.0 / 0.20.1 一致。
- `apksigner verify` 通过，`aapt` 确认包名、版本码 31、版本名 0.20.2、最低 API 26 和仅 ARM64。
- 201 项原生库及业务 assets 与 0.20.1 的逐项 SHA256 相同；比较排除构建生成的 dexopt 性能配置。

## Redmi Note 8 Pro 实机验证

1. 校验旧版 APK 与 GitHub 0.20.1 发布摘要一致，安装正式版 0.20.1 并冷启动成功，设备回读版本码 30。
2. 使用 `adb -s wsvwypiz7xwslvl7 install -r` 覆盖安装正式版 0.20.2，安装成功。
3. 冷启动返回 `Status: ok`，设备回读版本码 31、版本名 0.20.2，首页正常显示。
4. 检查桌面应用抽屉，新圆形图标的 PDF 字样、折角和编辑笔显示完整。

方形和浅色/深色主题图标已完成本地渲染检查，五种密度共 20 张图标的尺寸与透明通道检查通过。本次不改变手机的全局图标形状和主题设置；0.20.1 的 18 项设备功能回归结果保留在其独立验收记录中，本次未重复运行该设备测试集。

本机构建日志、制品核验 JSON、SHA256SUMS 与 Redmi 截图保存在 `app/build/release-0.20.2/`。
