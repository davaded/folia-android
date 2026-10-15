# Android x86_64 APK

<!-- packaging/android/README.md -->

本目录把主仓库的完整 Web 版打包成独立 Android APK。目标设备为 Android 13 / x86_64 车机。
不使用其他 Folia Android 移植仓库，不修改原版主题、视觉模式、帧率、动画、3D、EQ 或 AutoMix 设置。

前端使用原版 Vite 生产构建；所有构建资源都会拷入 APK。APK 自带 Mozilla 官方稳定 GeckoView
158.0.20261007201254，不依赖车机浏览器或系统 WebView 113。原版 HTMLAudioElement 双音轨、
Web Audio 音效图、FFT 与歌词时钟均继续运行；Android MediaSession 负责系统控制和音频焦点。
这仍是 Web 技术构建的界面，内置新内核本身不构成性能达标的证据。

范围是主项目 **Web 版**。Electron 专属的 Python 模型、人声分离和桌面窗口集成不属于此 APK，
也没有伪造 `window.electron` 来显示不存在的能力。

## 构建

需要 Node.js 24+、JDK 17、Android SDK platform 37 / build-tools 35，以及 Gradle 9.3.1。
原版根 `package-lock.json` 保持不变。

```sh
npm ci --ignore-scripts --no-audit --no-fund
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
node packaging/android/build-apk.mjs
```

`android/gradlew` 已固定 Gradle 9.3.1；如果本机没有 wrapper 下载权限，可通过
`FOLIA_GRADLE_BIN=/path/to/gradle-9.3.1/bin/gradle` 指定同版本 Gradle。
默认产物为 `android/app/build/outputs/apk/debug/app-debug.apk`。这是本地调试签名的非官方构建，
不能冒充上游正式发布。构建脚本只重建生成的 `assets/web/`，不删除用户音乐或源码。

`assets/web/android-build.json` 记录上游 revision、版本与前端资源 SHA-256。
根 HTML、Vite 配置不变；`src` 只兼容了扫码自检中“Android 不可能运行本机 API”的检测，
允许 APK 声明的同源原生代理，仍拒绝移动端误填的其他 localhost 服务。
Android 文件系统兼容脚本和调试诊断脚本仅注入 APK 内的 HTML 副本。

## 配套后端

使用主仓库的 [Docker Web 堆栈](../../deploy/docker/README.md)，对外提供一个可由车机访问的 HTTPS origin。
APK 加载本地界面，只有后端请求从本机 loopback 转发到该 origin：

| 路由 | 用途 |
| --- | --- |
| `/netease/` | 网易云搜索、登录与歌曲接口 |
| `/kugou/` | 酷狗接口 |
| `/qq/` | QQ 音乐接口 |
| `/api/` | AI 主题、歌词分词与歌词代理 |

配置后重新构建：

```sh
FOLIA_ANDROID_API_ORIGIN=https://folia.example.com node packaging/android/build-apk.mjs
```

地址必须是没有账号、密码、路径、query 或 fragment 的 HTTP(S) origin。
生产环境使用 HTTPS。音乐服务登录仍使用原版界面；AI 密钥只配置在后端容器，不能编入 APK。
切换 AI 服务同时设置 `VITE_AI_PROVIDER=openai` 或 `google`，并按上游 Docker 文档配置对应后端。
无后端配置时，API 返回明确错误；本地界面和已导入音乐仍可使用。

## 本地音乐

Gecko 的 OPFS 支持真实 FileSystemDirectoryHandle 持久化，但不提供 Chromium 的 showDirectoryPicker。
兼容层通过原生目录选择器选择文件，然后逐文件流式复制到应用私有 OPFS，返回真实 Handle，
由原版本地音乐库保存到 IndexedDB。未对本地歌曲制造假路径或伪造 Electron。

导入的是副本，额外占用车机存储；重新扫描会扫描副本，外部目录新增文件需要重新导入。
同名目录不会覆盖以前的导入；上游自行命名为 `Music (2)` 等。
当前原版本地库删除动作不负责清理这些 OPFS 副本，可通过 Android 应用存储管理清空应用数据。
如果文件选择器不存在，APK 提供原生浏览界面；只在用户选定目录后枚举该目录。

## 验证

`test/unit/android/filesystemCompat.test.ts` 验证取消、串行复制、真实 Handle 返回、路径校验、空间不足和失败清理。
调试 APK 的 `?androidDebug=1` 每五秒向仅调试构建开放的 loopback 端点报告帧率、API 支持与音频元素状态；
不采集账号凭据、歌名、音乐内容或个人播放列表。

设备安装、截图、导入、音频状态和后台运行的实际结果见本目录的 `DEVICE-VALIDATION.md`。
