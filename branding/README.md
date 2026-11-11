# PdfReader 应用图标

图标以折角 PDF 文档表示阅读，以金色编辑笔表示内容修改；墨绿配色延续应用默认主题。PDF 字母使用手绘矢量轮廓，不依赖构建机字体。设计与 SVG 均为项目内直接绘制，PNG 由 SVG 栅格化生成。

## 资产

| 文件 | 用途 |
| --- | --- |
| `pdfreader-icon-foreground.svg` | 彩色主体源文件，108 × 108 自适应画布 |
| `pdfreader-icon-monochrome.svg` | Android 13+ 主题图标源文件，字母及笔周围使用透明留白 |
| `pdfreader-icon.svg` / `.png` | 圆角方形图标，PNG 为 1024 × 1024 |
| `pdfreader-icon-round.svg` / `.png` | 圆形图标，PNG 为 1024 × 1024 |

Android Manifest 分别引用 `ic_launcher` 与 `ic_launcher_round`。两者提供 mdpi、hdpi、xhdpi、xxhdpi、xxxhdpi 五种密度，传统图标为 48/72/96/144/192 px；自适应前景与单色图层为 108/162/216/324/432 px。

Android 8+ 使用前景和完整背景分层，由桌面应用选择形状；Android 13+ 额外提供 `monochrome` 图层。系统主题图标的颜色由桌面决定。独立方形和圆形资源保留透明外角，前景的主要图形位于中心安全区。

背景渐变使用 `#23634C → #103C2F`，文档使用 `#FFFEF8 → #E9F0E8`，编辑笔使用 `#F2B866`。原生背景定义位于 `app/src/main/res/drawable/ic_launcher_background.xml`，颜色位于 `app/src/main/res/values/ic_launcher_background.xml`，调整时需与生成脚本同步。

## 重新生成

需要 Node.js 与可解析的 `sharp` 模块（本次使用 0.35.5）。在仓库根目录运行：

```sh
node scripts/generate-icons.cjs
```

若 `sharp` 位于独立工具目录，通过 `NODE_PATH` 指定该目录的 `node_modules`。此工具仅用于生成美术资源，不参与 Gradle 构建；构建应用无需安装 Node.js 或 sharp。

先修改前景和单色 SVG，再运行脚本；脚本会更新独立方形/圆形 SVG、PNG、Android 各密度资源，以及 README 引用的 `fastlane/metadata/android/en-US/images/icon.png`。不要单独修改生成的 PNG。

规范参考：[Android 自适应图标](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)。

## 本次验证

2026-11-11：`./gradlew :app:assembleDebug :app:lintDebug` 通过，Lint 无错误、无图标资源警告；20 张 Android PNG 的尺寸、透明通道和边角检查通过。调试包已在 Redmi Note 8 Pro（API 36）安装并启动，实机应用抽屉中的圆形图标显示完整。方形与浅色/深色主题图标完成本地渲染检查；未切换手机的全局桌面形状和主题设置。
