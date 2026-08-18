# IM 悬浮客户端（Android 原生 / Kotlin）

一个连接现有 Node 聊天室（`im/` 项目 `server.js`）的 Android 原生 App，包含：
- 登录 / 注册（HTTP 接口）
- 实时聊天（WebSocket，OkHttp）
- **全局悬浮窗输入框**：在任意界面点开悬浮窗，输入文字点右侧「发送」即可快速发消息，无需打开 App 内聊天框

技术栈：Kotlin + AndroidX + Material Design 3 + OkHttp（HTTP/WebSocket）+ 前台 Service + `WindowManager` 悬浮窗。
最低支持 Android 8.0（API 26）。

---

## 1. 用 Android Studio 打开

1. 打开 Android Studio → `File → Open` → 选择本目录 `ImClient`（含 `settings.gradle.kts` 的那层）。
2. 首次打开会自动下载 Gradle 8.9 与依赖（需联网）。
3. 用 USB 连接手机（开启「开发者选项 → USB 调试」），或新建一个 Android 模拟器（API 26+）。
4. 点击 ▶ Run 安装运行。

> 没有 Gradle wrapper jar 也没关系：Android Studio 导入时会按 `gradle-wrapper.properties` 自动拉取对应 Gradle 版本。

### 国内网络加速（已配置）

项目已把仓库切换到国内镜像，官方源仅作兜底：
- `settings.gradle.kts`：插件/依赖仓库 = 阿里云镜像（google / gradle-plugin / central / public）+ 官方兜底
- `gradle/wrapper/gradle-wrapper.properties`：Gradle 发行版从腾讯云镜像下载

如果同步还是慢/失败，可在 Android Studio 里 `File → Invalidate Caches / Restart` 后重新 `Sync Project with Gradle Files`。

---

## 2. 连上你的 IM 服务端

服务端 `server.js` 默认监听 `0.0.0.0:3001`（注意不是 README 里写的 3000）。先在本机把服务跑起来：

```bash
cd im
npm install
node server.js          # 或 npm run dev
```

App 登录页第一项「服务器地址」按运行环境填：

| 运行环境 | 填什么 | 说明 |
|----------|--------|------|
| Android 模拟器 | `10.0.2.2:3001` | 模拟器访问宿主机 PC 的保留地址 |
| 真机（同一 Wi-Fi） | `电脑局域网IP:3001`（如 `192.168.1.20:3001`） | 服务端已绑定 `0.0.0.0`，可直接访问 |

> 服务端是 HTTP（非 HTTPS），App 已在 Manifest 开启 `usesCleartextTraffic`，无需额外配置。

---

## 3. 开启悬浮窗权限

悬浮窗需要「显示在其他应用上层」权限（Android 6+ 的 `SYSTEM_ALERT_WINDOW`）：

1. 进入聊天界面，点右上角「悬浮窗：关」。
2. 若未授权，App 会跳转系统设置页，找到本应用并打开「允许显示在其他应用上层」，返回即可。
3. 授权后悬浮窗会显示在屏幕右下角，可拖动标题栏移动；在输入框打字、点右侧「发送」即发出。
4. 关闭按钮（×）可收起悬浮窗；开关状态会被记住。

> 悬浮窗由前台 Service 托管，因此即使把 App 切到后台或返回桌面，仍可随时快速发消息；长连接也会保持。

---

## 4. 项目结构

```
ImClient/
├── app/build.gradle.kts
└── app/src/main/
    ├── AndroidManifest.xml          # 权限 + Service 声明
    ├── java/com/example/imclient/
    │   ├── MainActivity.kt          # 登录 / 注册 + 服务器地址
    │   ├── ChatActivity.kt          # 聊天界面 + 悬浮窗开关 + 登出
    │   ├── ChatService.kt           # 前台服务：长连接 + 悬浮窗
    │   ├── WsClient.kt              # WebSocket 管理（重连/乐观消息）
    │   ├── Api.kt                   # HTTP 登录/注册
    │   ├── SessionManager.kt        # token/host/开关 持久化
    │   ├── model/Message.kt         # 消息模型
    │   └── MessageAdapter.kt        # 消息列表适配器
    └── res/                        # 布局、字符串、主题、图标、气泡背景
```

## 5. 与服务端协议的对应关系

| 能力 | 实现 |
|------|------|
| 登录 `POST /api/login` | `Api.login` |
| 注册 `POST /api/register` | `Api.register` |
| 连接后鉴权 `{type:"register",user,token}` | `WsClient.connect` |
| 发文字 `{type:"text",text,_clientId}` | `WsClient.sendText`（带乐观消息 + clientId 回显更新） |
| 收历史 `history` / 在线 `online_users` / 被踢 `kicked` | `WsClient` 解析并回调 |
| 断线重连 | `WsClient.scheduleReconnect`（3s 重试，被踢则停止） |

## 6. 已知限制 / 可扩展点

- 仅实现文字消息；图片/文件发送接口已预留（`image`/`file` 类型），可在 `WsClient` 扩展。
- 历史消息只拉「当天」（与服务端 `history` 推送一致）；跨天历史可额外调用 `/api/history/messages?date=`。
- 悬浮窗为固定小卡片；可进一步做成「气泡→展开」的聊天头（Chat Head）交互。
- 当前明文 HTTP；如需上线建议改用 HTTPS + WSS，并相应调整 `SessionManager` 中的 `httpBase`/`wsUrl`。
