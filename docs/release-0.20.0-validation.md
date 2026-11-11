# PdfReader 0.20.0 验收记录

日期：2026-11-11（北京时间）。本次按用户确认，仅以 POCO F2 Pro 真机验收；未运行模拟器。

## 应用与精简

- 正式包名 `com.loooong.pdfreader`，版本 `0.20.0`，versionCode `29`。
- 独立应用，与旧“阅读”安装身份不同；旧数据不自动迁移。Debug 包追加 `.debug`。
- 删除 EPUB/TXT Activity、界面、解析、书架、偏好、测试及 Readium 依赖；PDF 查看、编辑、主题、更新保留。
- 首页保留阅读 PDF、编辑 PDF、合并 PDF 和阅读历史，保持原有视觉风格。

## 修复与优化

- 以独立文档快照统一页面/文档撤销，避免恢复后访问已关闭对象；失败编辑回滚并更新草稿路径。
- 编辑、保存与撤销共享同步门禁和文档锁；新文档先准备解析器再接管来源身份。
- 覆盖及恢复同时失败时持久保留原始备份并支持导出；另存、页面导出、签名与合并目标检查同源 URI。
- 恢复草稿同步恢复来源指纹；内容、批注、填表、页面组装、复制和打印按 PDF 独立权限执行。
- 阅读后台渲染、位图尺寸预算、按字节缓存、可见页保护、离屏任务取消、最新宽度补渲染及安全关闭资源。
- 修复大页面高清区域坐标；全文搜索后台执行、协作取消、有界摘要和 5000 处结果上限。
- Activity 重建不重复消费外部打开 Intent，保留阅读位置与未保存编辑。
- PDF 序列化在独立对象图上完整写入，保留原加密参数、密码和权限；不包含增量历史中的旧内容。
- 补齐手工文本流的字体/字形子集登记；内置静态霞鹜文楷轻便版，过滤不能可靠保存的 CFF/可变字体。
- 路径线帽与拐角样式写为 PDF 要求的整数，兼容严格解析器。
- 更新按 PdfReader 资产名选择；安装前校验 SHA256、包名、版本码、版本名和签名。

## 验证方式

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/release.py build
adb -s 31936bb6 shell am instrument -w com.loooong.pdfreader.debug.test/androidx.test.runner.AndroidJUnitRunner
```

设备：POCO F2 Pro（`31936bb6`），Android API 36。设备回归覆盖混合历史与资源、失败回滚、同源覆盖保护、并发保存、长页预算与请求宽度、高清区域、批注权限、原密码保存、连续中文编辑、草稿恢复、打开失败、资源关闭、外部打开后 Activity 重建，以及图片替换、压缩和中文水印。

正式 APK 通过 `apksigner verify`；在真机实际完成首页 → 文件选择器 → 连续阅读 → 编辑模式 → 插入矩形 → 另存为，输出通过独立 PDF 解析及渲染检查。浅色与深色首页、阅读与编辑截图保存在本机 `app/build/acceptance/0.20.0/`（构建目录，不进入源码）。

## 最终结果

- Debug 与 Release 单元测试分别 234 项：233 通过、1 个既有跳过项、0 失败。
- POCO F2 Pro 完整设备回归：17 项全部通过。
- Release Lint：0 错误、47 警告、6 提示；R8 与资源压缩构建通过。
- 正式 APK：66,934,240 字节；SHA256 `2884db57b202d0c68386ada3ea1d0b21b735e83dacdb54c1bd186e420d9817d1`。
- 删除电子书依赖后补入约 14 MB 的静态中文字体以修复实际中文保存失败；最终包比 0.19.1 增加约 5.1 MB，未宣称包体缩小。

## 发布签名与来源

- 专用 RSA 3072 位签名，证书 SHA256：`dba699de81201f840f0ec15d7fbc68ae8c3dffee20fe7647dddac9d8d7902eed`。
- 密钥位于仓库外 `~/.local/share/PdfReader/signing/PdfReader-release.p12`；密码位于登录钥匙串服务 `PdfReader-release-signing`。
- 字体：LXGWWenKaiLite-Regular.ttf，作者仓库 `lxgw/LxgwWenKai-Lite`，固定版本 `v1.530`，附 SIL OFL 1.1 全文。SHA256：`f799a538a3355877e597a0ae04050e897932c9ae7689eb9227c2953c5dd549ea`。
- 本轮未升级 AGP、Kotlin 或 PDFBox；字体子集适配器针对当前锁定的 PDFBox Android `2.0.27.0`，升级依赖时必须保留加密与中文回归。

## 验证边界

现有 JVM 图片插入测试仍保留其原有跳过标记，Android 图片读写由设备测试覆盖。Lint 无错误但有既有风格/依赖警告；这些结果不代表覆盖所有第三方 PDF、所有字体或所有 Android 设备。旧“阅读”的已发布客户端不能通过本次源码修改获得新包名的覆盖升级能力。
