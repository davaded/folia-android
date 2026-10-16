# 车机版改动记录（Claude）

<!-- packaging/android/CHANGES.md -->

Claude 与 Codex 一起维护车机版时，用这份文档同步信息。每次改动追加一条，最新的写在最上面。
只记改了什么、为什么改、怎么验证的、还留下什么问题；细节看对应提交。

## 仓库约定

- 私有仓库：`davaded/folia-android`，默认分支 `main`。
- 本地分支 `codex/android-web-x86` 跟踪远程 `android/main`，推送命令为 `git push android codex/android-web-x86:main`。
- 远程 `origin` 仍指向上游 `chthollyphile/folia-major`，只用来拉取，不往上游推送。
- 仓库曾以公开状态存在约十分钟（2026-10-15），之后改为私有。当时只有上游历史和首个打包提交，不含私密内容。
- 车机 hook（Frida 等）只放在这个私有仓库，车机连接信息（frp 地址、端口）不要写进代码。

## 2026-10-16 · 本次提交 · 车机裁剪：去除键盘快捷键、桌面窗口入口与快捷命令

用户的决定：
- 键盘快捷键类和桌面窗口类入口在车机上全部去掉。
- 快捷命令也去掉（要先打开命令窗口才能点，用处有限）。
- 保留「自动隐藏控制栏」：触屏一碰就显示，松手 3 秒后收起。
- 触屏快捷按钮、方向盘按键以后再做；不做壳层的沉浸开关（APK 已经隐藏了状态栏和导航栏）。

改动（都基于 `isAndroidApkRuntime()` / 命令 `platform: ['web', 'electron']`）：
- **设置导航**：锚点新增 `hiddenOnAndroid`，`buildSettingsNavGroups` 新增 `isAndroidApk`。`gridPaletteHotkey`、`customShortcut`、`pinnedCommands` 在车机上不出现在导航里。
- **键盘快捷键类**：
  - 交互设置里的「海报墙上用 S 键打开命令窗口」「自定义快捷键」不渲染，`settings-custom-shortcut` 命令不出现；
  - 执行模式命令 `execute-mode` 不出现，`:` 热键随之失效；
  - 设置帮助页的「快捷键」「播放控制」两张按键表不显示；
  - 海报墙和 Lattice 右下角工具面板里，「操作提示」帮助（全是键盘操作）不显示，快捷格和条目上的按键标签（`: + R`、`Ctrl + B` 等）不显示。
- **快捷命令**：
  - 界面设置里的「快捷命令」不渲染，`settings-pinned-commands` 命令不出现；
  - 命令窗口下方的三格（默认是上一首、下一首、队列）改走 `resolveVisiblePinnedCommandSlots`，车机上全部为空，这一排不显示。
- **桌面窗口类**：
  - 外观设置的「同时隐藏鼠标指针」、实验室的「始终显示标题栏控制按钮」不渲染；
  - `settings-toggle-cursor-auto-hide`、`settings-toggle-main-window-titlebar`、`browser-fullscreen` 命令不出现。

验证：
- `tsc --noEmit` 通过；单测新增了导航锚点和命令可见性的车机用例，全量单测只剩 `crashLog` 那 2 条原有失败。
- 用 `--web-only` 构建后在浏览器里核对：
  - 帮助页没有按键表；
  - 交互控制只剩「海报墙操作按钮」；
  - 「自动隐藏控制栏」「进入应用后自动播放」仍在；
  - 鼠标指针、S 键、自定义快捷键、标题栏按钮都不显示。
- 海报墙工具面板、快捷命令（之后才补上）没有在浏览器里实际打开核对，只做了代码检查和单测。

留下的问题：
- 「思索」教程和用户指南里仍有键盘操作的说明。
- 自定义快捷键在运行时的监听还在（车机上没有键盘，触发不到），只是入口隐藏了。

## 2026-10-16 · `41a8ef13` 车机裁剪：去除 AI 主题、AI 分词与 OBS 入口

用户的决定：
- 不要 AI 主题和 AI 分词，整个分词编辑器也不要。
- OBS 不要；歌词 API、视频导出本来就是桌面专属，不用处理。
- 歌曲主题保持现状：保留封面取色，不改界面上的文案。

改动：
- **平台标识**：`build-apk.mjs` 往 APK 内的 `runtime-config.js` 写入 `platform = 'android'`。
  - `runtimeConfig.ts` 新增 `isAndroidApkRuntime()` 和 `areAiFeaturesAvailable()`。
  - 命令面板新增 `'android'` 平台；`'web'` 不再包含 APK，`platform: ['web', 'electron']` 表示"车机上不出现"。
- **AI 主题**：在 APK 中生成来源固定为封面取色，读存储和切换来源两处都做了拦截。
  - 隐藏的入口：外观设置的"主题生成来源"、主题乐园的 AI 页、快速编辑器的"手动导入 AI 主题"、`theme-source-ai` / `theme-source-cover` 命令。
- **分词**：`lyric-segmentation` 命令和播放面板的分词按钮不显示，`isLyricSegmentationAiAvailable()` 返回 false。已保存的分词照常生效。
- **OBS**：隐藏外观设置里的"复制 OBS URL / CSS"按钮，以及 `settings-obs-copy-css` 命令。
- **本机代理**：`LocalAssetServer` 对 `/api/generate-theme`、`/api/generate-theme_openai`、`/api/segment-lyrics` 直接返回 404 `API_DISABLED`，不转发。
- **文档**：根 README 新增"车机版"一节，`packaging/android/README.md` 同步更新。

验证：
- `tsc --noEmit` 通过。
- 新增或修改的单测都通过。全量单测里只有 `test/unit/electron/crashLog.test.ts` 的 2 条失败，改动前就存在，与本次无关。
- 跑了 `build-apk.mjs --web-only`，并在浏览器里打开 APK 的 Web 产物，确认设置页里主题来源和 OBS 按钮已经消失。
- 完整 APK 没有构建：本机没有 JDK。用户会自己构建并安装。

留下的问题：
- 界面上仍有"AI 主题"字样。它是每首歌主题槽位的名字，在 APK 里实际装的是封面取色主题。
- "播放页透明背景"开关（说明里写着给 OBS / 抠像用）没有隐藏，等用户决定。
- 只构建 Web 部分时，`assets/web/android-build.json` 里会记成"未配置后端"。完整构建会重新生成它。

## 2026-10-15 · `4f324328` 首次提交 Android 打包

`android/`、`packaging/android/` 和扫码自检的同源代理改动是 Codex 的工作。Claude 只做了这些事：
- 跑了相关单测（22 条通过）。
- 确认构建产物和 `assets/web/` 已被 `.gitignore` 排除，提交内容里没有后端地址或密钥。
- 提交，创建仓库并推送。

## 已发现、尚未处理的问题

这些来自 2026-10-15 的车机日志（`adb logcat`，过滤 Folia / Gecko）和代码梳理，还没有排期。

- **自动播放权限**：启动时有两次 `No listener for GeckoView:ContentPermission`，壳层没设 PermissionDelegate，大概率挡住了上车自动续播。
- **Service Worker 缓存**：APK 仍注册 `sw.js`。装新 APK 后第一次启动会先用旧缓存，第二次才是新版。本地封面路由挂在这个 SW 上，不能直接去掉。
- **音频缓存没有上限**：Web 端不显示容量上限设置。实测约一小时后，私有数据有 files 60M + cache 104M。
- **本地导入**：复制目录时不按扩展名过滤；重新导入会再复制一份，旧副本不会清理。
- **音频焦点**：把"压低音量"（duck）当成短暂失焦处理，导航播报时会暂停音乐。需要开着导航实测。
- **后台可见性**：切到后台后 `visibilityState` 仍是 `visible`，依赖可见性变化降负载的逻辑不会触发。
- **帧率**：播放页播放时大多在 17–25fps，首页约 33fps。
- **触屏上没用的入口**：键盘与桌面窗口类已在车机上去掉（见上）。还剩讲悬停、右键、拖放操作的"思索"教程。另外，"总是显示主窗口标题栏"在普通 Web 版上仍会显示，属于上游现有 bug，没有处理。
- **首次引导**：要求按住 Ctrl+G 完成一次思索。思索里提到有触屏按钮，但车机上是否走这条路径还没确认。
- **波点音乐**：只能在桌面版用，Web 上显示为灰色的"未配置"。车机上要么隐藏，要么另做后端代理。
