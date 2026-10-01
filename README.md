# Eridanus 助理 (Android 悬浮窗应用)



基于 **Eridanus** 智能助理的 Android 伴侣端，提供屏幕内容智能感知、多模态翻译与实时建议。



---



## 🌟 核心特性

1. **中转服务连接**：连接至 Eridanus 后端 Hub（`http://<IP>:5007`），**无需二次启动 Bot 核心及 Python 插件**。

2. **QQ 记忆与上下文共享**：在 App 中配置绑定的 QQ 号 `user_id`，所有来自 App 的提问与互动均在 Eridanus 内部以对应 QQ 号身份路由，共享完整上下文与长期记忆。

3. **流畅聊天与即时同步**：

   - 采用实时增量轮询机制（`since_id` 过滤），多端消息毫秒级同步。

   - 深度支持多段消息按 `||` 切分打字效果与自然延迟，模拟真人聊天体验。

   - 内存与本地磁盘二级图片缓存（LRU），聊天记录上下滚动无缝加载不闪烁。

4. **屏幕悬浮球与卡片**：

   - 全局悬浮球贴边吸附，点击即呼出助理分析面板。

   - 支持主动感知（每隔4秒自动感知屏幕变化）与被动点击手动感知。

5. **多语言与屏幕建议**：

   - 内置 Google ML Kit 离线多语言文本识别（OCR），即使身处海外（如俄语、小语种等应用界面）也能轻松理解。

   - AI 结合当前屏幕画面与上下文，自动翻译核心按键/功能并给出下一步操作指导。



---



## 📱 更新日志



### v1.2.0 (VersionCode: 8)
- **类似 QQ 的聊天大图查看器**：点击聊天记录中的任意图片（用户发送或机器人回复）即可进入沉浸式全屏大图预览。
- **手势缩放与拖动平移**：支持双指捏合缩放（Pinch-to-zoom）、双击快捷缩放/还原（Double-tap zoom）、缩放后平滑拖动查看细节，单点图片即可隐藏/呼出操作栏。
- **一键无损保存至系统相册**：大图界面底部提供快捷保存按钮，适配 Android 10+ (API 29+) Scoped Storage (MediaStore) 及旧版本存储权限，原画无损保存到手机系统相册（Pictures/Eridanus）。

### v1.1.9 (VersionCode: 7)

- **彻底杜绝乱码**：所有客户端文本与提示符采用 Unicode 严格保护，彻底消除 Windows 构建管道下的问号乱码现象。

- **实时消息与图片推送机制升级**：重构 `ChatAdapter` 精准 ID 匹配更新，无论是分段文本还是异步生成的图片均可即刻无感插入并平滑滚动，无需退出重进。

- **构建环境 UTF-8 强化**：Gradle 与 Kotlin Daemon 编译参数强制统一为 UTF-8。



### v1.1.0 (VersionCode: 2)

- **增量历史与会话同步**：支持按 `since_id` 过滤拉取历史，AI 思考状态占位符在得到回复后原地替换。

- **优雅超时与异常兜底**：网络断开或长任务等待超时提示优化。

- **动态版本读取与回显**：设置页面自动同步当前 APK 版本号。



### v1.0.0 (VersionCode: 1)

- 初始版本发布，支持全局悬浮球、屏幕截屏捕获、OCR 初筛、Eridanus WebUI Hub 鉴权中转与 QQ 账号记忆绑定。



---



## 🛠️ 构建与编译

- **编译环境**：Android Studio Iguana / Jellyfish / Ladybug 及以上，JDK 17 或 1.8，Android SDK 24 ~ 34。

- **打包命令**：

  ```cmd

  cd /d D:\coding\pyc\Eridanus-Android

  gradlew.bat assembleDebug

  ```

- **安装包输出**：`app/build/outputs/apk/debug/app-debug.apk`



---



## 🚀 快速上手

1. **中转服务器地址**：填入部署 Eridanus 的外部 IP 地址与端口，例如 `http://192.168.1.50:5007`。

2. **鉴权 Token**：若服务端的 `basic_config.yaml` 开启了 WebUI 访问 token，在此填入对应 token；若未开启则可留空。

3. **绑定 QQ 号**：填入你在 QQ 上与机器人对话的 QQ 账号（例如 `1840094972`），实现记忆互通。

4. **自定义助理名**：可自定义 Bot 在界面与悬浮窗显示的昵称（默认为 `Eridanus`）。

---



## 🤖 GitHub Actions CI/CD 自动构建与发版

项目已完整配置 GitHub Actions 自动化工作流（.github/workflows/build-and-release.yml）：

1. **自动构建与产物上传**：每次推送到 main 或 master 分支时，云端自动使用 JDK 17 与 Android SDK 编译 Debug APK，并在 Actions 页面提供 30 天的安装包 Artifacts 下载。

2. **打 Tag 自动发布 Release**：只需打上版本 Tag 并推送至 GitHub：

   `ash

   git tag v1.1.9

   git push origin v1.1.9

   `

   工作流将自动捕获版本号，并在 GitHub Releases 页面自动发布新版本，直接将打包好的 Eridanus-Assistant-v1.1.9.apk 附加到 Release 附件供随时下载！

3. **网页端一键手动触发**：支持在 GitHub 网页的 **Actions** -> **Build and Release Android APK** 中点击 **Run workflow** 手动构建或发版。
