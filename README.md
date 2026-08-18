# 聊天室 - 即时通讯 Web 应用

一个基于 Node.js 的轻量级即时通讯聊天室，支持文字消息、图片/文件上传、消息回复、消息搜索、在线状态、历史记录等功能。

## 技术栈

| 技术 | 用途 |
|------|------|
| **Express** | HTTP 服务框架 |
| **ws** | WebSocket 实时双向通信 |
| **better-sqlite3** | 本地 SQLite 数据库，无需安装数据库服务 |
| **multer** | 文件上传处理 |
| **crypto** | SHA-256 密码哈希加密 |
| **PWA** | 手机全屏沉浸式体验 |

## 快速开始

### 环境要求

- Node.js 16+
- npm

### 安装与运行

```bash
# 安装依赖
npm install

# 开发模式运行（热重载）
npm run dev

# 生产运行（发包）
npm start        # 等价于 node server.js
```

启动后访问 `http://localhost:3001` 即可（服务端实际端口为 **3001**，可通过 `PORT` 环境变量修改）。

## 功能特性

### 用户系统
- **注册/登录** - 用户名 + 密码，密码使用 SHA-256 不可逆加密存储
- **Token 认证** - 登录后生成随机 token，刷新页面无需重新登录
- **单设备登录** - 同一账号在另一设备登录时，原设备会被踢下线并收到提示
- **退出登录** - 一键退出，清除本地登录状态

### 消息系统
- **文字消息** - 支持任意文本内容
- **图片上传** - 点击附件图标选择图片，实时预览
- **文件上传** - 任意大小文件，显示上传进度条，对方可下载
- **消息回复/引用** - 右键消息可回复，显示被回复内容预览
- **消息搜索** - 双击聊天室标题打开搜索栏，关键词搜索并跳转到对应消息
- **消息状态** - 发送中(⏳)、发送成功(✓)、发送失败(✗)

### 实时交互
- **WebSocket 实时通信** - 消息即时推送，无延迟
- **在线用户列表** - 用户名旁显示绿色圆点表示在线
- **新成员加入通知** - 系统消息提示 + 提示音效
- **消息提示音** - 收到消息时播放提示音，支持移动端

### 通知功能
- **桌面通知弹窗** - 右下角弹出系统风格通知框，显示发送者和消息内容
  - 复制按钮 - 一键复制消息内容
  - 跳转查看 - 点击跳转到聊天框中对应消息位置
  - 自动消失 - 4秒倒计时进度条后自动关闭
- **提示音效** - 新成员加入和收到消息两种音效

### 历史与搜索
- **按日期查看历史** - 左侧菜单按日期列出所有有消息的日期，点击加载对应历史
- **消息搜索** - 搜索关键词，高亮定位到匹配消息

### 界面特性
- **自动换行输入框** - 类似 QQ 的输入框，自动扩展高度
- **消息复制** - 文字消息右侧有复制按钮
- **图片预览** - 点击图片放大查看
- **PWA 全屏** - 手机端添加到主屏幕后全屏沉浸体验
- **响应式设计** - 适配电脑和手机端

## 项目结构

按 **三大类** 划分目录：`node/`（Node JS 网页端）、`python/`（Python 桌面版）、`android/`（安卓客户端）。
三端共用同一套 HTTP API + WebSocket 协议，前端 `node/public/` 为 Node 与 Python 桌面版共用。

```
im/
├── node/                    # ★ Node JS 网页端
│   ├── server.js            # 服务端（Express + WebSocket + SQLite）
│   ├── package.json         # 依赖与脚本（npm start 启动）
│   ├── start.bat            # Windows 一键启动
│   └── public/              # 前端页面（index.html / sw.js / manifest.json / sounds）
├── python/                  # ★ Python 桌面版
│   ├── run_gui.py           # 控制中心 GUI（pywebview + 看门狗自动重启）
│   ├── server.py            # Flask 版服务端（Node 的完整镜像 + 安全面板）
│   ├── run.py               # 命令行启动入口
│   ├── pack.bat             # 一键打包为 聊天室.exe（见下「一键脚本」）
│   ├── start.bat            # 命令行启动服务端（python run.py）
│   ├── start_gui.bat        # 启动控制中心 GUI（python run_gui.py）
│   └── requirements.txt     # Python 依赖
├── node/
│   └── start.bat            # Windows 一键启动（yarn dev / npm start）
├── android/                 # ★ 安卓客户端（Kotlin + Android Studio）
│   └── ImClient/            # 登录/聊天/悬浮窗快速发送/图片文件收发
├── data/                    # 运行数据（SQLite，自动创建，不入库）
├── uploads/                 # 上传文件目录（自动创建，不入库）
├── dist/                    # 构建产物（apk/exe，不入库）
├── 聊天室.spec              # PyInstaller 打包配置（Python 桌面版）
└── README.md
```

## GitHub 发布

仓库已配置 `.gitignore`（排除 node_modules / data / uploads / dist / build / Gradle 缓存 / 虚拟环境等）。克隆后按端启动：

```bash
# 1) Node JS 网页端
cd node && npm install && npm start    # 访问 http://localhost:3001

# 2) Python 桌面版
cd python && pip install -r requirements.txt && python run_gui.py   # 或运行 pack.bat 打包 exe

# 3) Android 客户端
用 Android Studio 打开 android/ImClient 直接构建（注意首次同步需联网下载 Gradle）
```

## 一键脚本（`.bat`，已被 `.gitignore` 排除，不进仓库）

仓库根目录 `.gitignore` 用 `*.bat` 规则把所有批处理脚本都屏蔽了（避免把平台相关脚本误传）。这些脚本**不会随 `git clone` 下载到本地**，如需使用请按下方内容自行在对应目录创建，或按说明操作。

### Python 桌面版

| 脚本 | 位置 | 作用 | 内容 |
|------|------|------|------|
| `start_gui.bat` | `python/` | 启动控制中心 GUI（pywebview 窗口） | `python "%~dp0run_gui.py"` |
| `start.bat` | `python/` | 仅命令行启动服务端（无 GUI） | `python "%~dp0run.py"` |
| `pack.bat` | `python/` | **一键打包为 `dist\聊天室.exe`** | 见下方完整脚本 |

**`pack.bat`（一键打包为 exe）：**

```bat
@echo off
setlocal
rem 切换到项目根目录（pack.bat 位于 python/ 下，根目录为上一级）
cd /d "%~dp0.."

rem 直接调用 venv 内的 python（不依赖 activate，目录改名/移动后依然稳定）
set VENV_PY=python\.venv\Scripts\python.exe
if not exist "%VENV_PY%" set VENV_PY=python

rem 安装依赖与打包工具
"%VENV_PY%" -m pip install -r python\requirements.txt
"%VENV_PY%" -m pip install pyinstaller

rem 打包为单文件无控制台 exe（前端 node/public 打进 bundle 的 public）
"%VENV_PY%" -m PyInstaller --onefile --noconsole --name "聊天室" --add-data "node/public;public" --paths "python" --hidden-import flask --hidden-import flask_sock --hidden-import webview python\run_gui.py

echo Build complete. The executable is in the "dist" folder as 聊天室.exe
endlocal
```

> 打包说明：
> - 建议在 `python\.venv` 虚拟环境中打包（脚本会自动优先使用），避免污染系统 Python。
> - 产物 `dist\聊天室.exe` 为**单文件**，可直接双击运行；首次启动会自动初始化数据库与系统账号。
> - `dist/` 已被 `.gitignore` 排除，不会上传。

### Node JS 网页端

| 脚本 | 位置 | 作用 | 内容 |
|------|------|------|------|
| `start.bat` | `node/` | Windows 一键启动开发模式 | `cd /d "%~dp0" && yarn dev` |

> 若没有 `yarn`，把 `start.bat` 里的 `yarn dev` 改成 `npm start` 即可。

## API 接口

### HTTP 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/register` | 注册新用户 |
| POST | `/api/login` | 用户登录 |
| POST | `/upload` | 上传文件/图片 |
| GET | `/api/history/dates` | 获取有消息的日期列表 |
| GET | `/api/history/messages?date=YYYY-MM-DD` | 获取指定日期消息 |
| GET | `/api/search?q=关键词` | 搜索消息 |

### WebSocket 消息

**客户端 → 服务端：**

| 类型 | 说明 |
|------|------|
| `register` | 用户注册连接（携带 user + token） |
| `text` | 发送文字消息 |
| `image` | 发送图片消息（携带 image_url） |
| `file` | 发送文件消息（携带 image_url + file_name + file_size） |

**服务端 → 客户端：**

| 类型 | 说明 |
|------|------|
| `registered` | 认证结果 |
| `history` | 当日历史消息 |
| `online_users` | 在线用户列表 |
| `kicked` | 被踢下线通知 |
| `system_join` | 新成员加入通知 |
| `user_joined` | 用户加入群聊 |

## 数据库结构

### users 表
| 字段 | 类型 | 说明 |
|------|------|------|
| username | TEXT | 用户名（主键） |
| password_hash | TEXT | SHA-256 密码哈希 |
| token | TEXT | 登录令牌 |

### messages 表
| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 自增主键 |
| user | TEXT | 发送者 |
| text | TEXT | 消息内容 |
| type | TEXT | 类型(text/image/file/system_join) |
| image_url | TEXT | 图片/文件 URL |
| file_name | TEXT | 文件名 |
| file_size | INTEGER | 文件大小 |
| reply_to | INTEGER | 回复的消息 ID |
| created_at | TEXT | 创建时间 |

## 开发说明

```bash
# 开发模式（nodemon 热重载）
npm run dev

# 生产模式
node server.js
```

默认监听 `0.0.0.0:3001`，可通过 `PORT` 环境变量修改端口。