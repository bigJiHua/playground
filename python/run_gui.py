#!/usr/bin/env python
"""聊天室控制中心（启用者面板）。

设计目标（对应“py 版会突然失去服务”的问题）：
- 服务作为**受监管的 subprocess** 运行，而非与 GUI 同进程的线程。
  一旦服务进程崩溃，看门狗会**自动重启**它，避免“失去服务”。
- 提供一个本地控制面板（pywebview 窗口），实时显示：
  服务状态、在线人数、运行时长、本机/局域网访问地址；
  并提供 启动 / 重启 / 刷新 / 打开聊天室 按钮。
- 冻结成 exe 后，单一可执行文件既可作为 GUI 控制中心启动，
  也可通过 --server 参数作为服务子进程自举，无需额外文件。

服务模式（run_server_mode）直接复用 server.py 的 Flask 应用。
"""

import os
import sys
import time
import json
import socket
import ctypes
import secrets
import threading
import subprocess
import webbrowser
import urllib.request
import urllib.parse


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def find_free_port_local(preferred, host='127.0.0.1', max_tries=100):
    for p in range(preferred, preferred + max_tries):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind((host, p))
                return p
            except OSError:
                continue
    return preferred


def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        return s.getsockname()[0]
    except Exception:
        return '127.0.0.1'
    finally:
        s.close()


def set_clipboard(text):
    """写入 Windows 系统剪贴板（UTF-16 文本，无第三方依赖，冻结 exe 友好）。
    供聊天 iframe 的“剪贴板同步”功能调用：收到对方消息 → 主面板桥接 → 写本机剪贴板。
    """
    try:
        if not isinstance(text, str):
            text = str(text)
        text = text.replace('\r\n', '\n').replace('\n', '\r\n')
        data = text.encode('utf-16-le') + b'\x00\x00'
        CF_UNICODETEXT = 13
        GMEM_MOVEABLE = 0x0002
        GMEM_ZEROINIT = 0x0040
        user32 = ctypes.windll.user32
        kernel32 = ctypes.windll.kernel32
        # 64 位 Windows 必须显式声明签名，否则句柄/指针会被截断为 32 位
        user32.OpenClipboard.argtypes = [ctypes.c_void_p]
        user32.OpenClipboard.restype = ctypes.c_int
        user32.EmptyClipboard.restype = ctypes.c_int
        user32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
        user32.SetClipboardData.restype = ctypes.c_void_p
        user32.CloseClipboard.restype = ctypes.c_int
        kernel32.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
        kernel32.GlobalAlloc.restype = ctypes.c_void_p
        kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
        kernel32.GlobalLock.restype = ctypes.c_void_p
        kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
        kernel32.GlobalUnlock.restype = ctypes.c_int
        kernel32.GlobalFree.argtypes = [ctypes.c_void_p]
        kernel32.GlobalFree.restype = ctypes.c_void_p
        if not user32.OpenClipboard(0):
            return {'ok': False, 'error': 'OpenClipboard failed'}
        try:
            user32.EmptyClipboard()
            h = kernel32.GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, len(data))
            if not h:
                return {'ok': False, 'error': 'GlobalAlloc failed'}
            ptr = kernel32.GlobalLock(h)
            if not ptr:
                kernel32.GlobalFree(h)
                return {'ok': False, 'error': 'GlobalLock failed'}
            ctypes.memmove(ptr, data, len(data))
            kernel32.GlobalUnlock(h)
            if not user32.SetClipboardData(CF_UNICODETEXT, h):
                kernel32.GlobalFree(h)
                return {'ok': False, 'error': 'SetClipboardData failed'}
        finally:
            user32.CloseClipboard()
        return {'ok': True}
    except Exception as e:
        return {'ok': False, 'error': str(e)}


# ---------------------------------------------------------------------------
# 服务模式：作为子进程运行 server.py 里的 Flask 应用
# ---------------------------------------------------------------------------
def run_server_mode(port, secret=None):
    import server  # 在子进程内导入（源码与冻结 exe 均可用）

    # 把 stdout/stderr 落盘到 data/server.log 并加时间戳。
    # 冻结 exe 是 --noconsole，服务一旦崩溃过去完全没有痕迹，
    # 只能靠猜；有日志后「刷新后掉线」这类问题可以直接看死因。
    try:
        _base = os.path.dirname(sys.executable) if getattr(sys, 'frozen', False) \
            else os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
        _log_dir = os.path.join(_base, 'data')
        os.makedirs(_log_dir, exist_ok=True)
        _f = open(os.path.join(_log_dir, 'server.log'), 'a', encoding='utf-8', buffering=1)

        class _Timestamped:
            def __init__(self, f):
                self._f = f

            def write(self, data):
                try:
                    if not data or not data.strip():
                        return
                    ts = time.strftime('%Y-%m-%d %H:%M:%S')
                    for line in data.rstrip().splitlines():
                        self._f.write(f'[{ts}] {line}\n')
                except Exception:
                    pass

            def flush(self):
                try:
                    self._f.flush()
                except Exception:
                    pass

        sys.stdout = _Timestamped(_f)
        sys.stderr = _Timestamped(_f)
        # 让 Flask / werkzeug 的异常也走同一份日志
        import logging
        logging.basicConfig(stream=sys.stdout, level=logging.ERROR)
    except Exception:
        pass

    server.init_db()
    server.ensure_admin()
    server.ensure_system_user()
    if secret:
        server.SERVER_SECRET = secret
    server.start_heartbeat(interval=25)
    server.app.run(host='0.0.0.0', port=port, threaded=True, use_reloader=False)


# ---------------------------------------------------------------------------
# 控制器：管理服务的生命周期 + 看门狗自动重启 + 状态查询
# ---------------------------------------------------------------------------
class Controller:
    MAX_AUTO_RESTARTS = 10

    def __init__(self):
        self.default_port = int(os.getenv('PORT', '3001'))
        self.port = find_free_port_local(self.default_port)
        self.port_conflict = self.port != self.default_port  # 默认端口被其他实例/程序占用
        self.secret = secrets.token_hex(8)  # GUI 自动登录用的服务端 secret（每次启动重新生成）
        self.proc = None
        self.lock = threading.Lock()
        self.start_time = 0
        self.auto_restart = True
        self.crash_count = 0
        self.restart_count = 0   # 看门狗累计重启次数（面板可见，掉线不再是"莫名其妙"）
        self.watchdog_started = False
        self.log_f = None

    def _build_args(self):
        if getattr(sys, 'frozen', False):
            # 冻结 exe：以 --server 方式重新启动自身作为服务进程
            return [sys.executable, '--server', '--port', str(self.port), '--secret', self.secret]
        here = os.path.dirname(os.path.abspath(__file__))
        server_script = os.path.join(here, 'server.py')
        return [sys.executable, server_script, '--port', str(self.port), '--secret', self.secret]

    def _spawn(self):
        args = self._build_args()
        self.proc = subprocess.Popen(
            args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        self.start_time = time.time()
        self.crash_count = 0

    def start_server(self):
        with self.lock:
            if self.proc and self.proc.poll() is None:
                return self.status()
            self.auto_restart = True
            try:
                self._spawn()
            except Exception as e:
                return {'running': False, 'error': str(e), 'port': self.port}
        self._ensure_watchdog()
        return self.status()

    def stop_server(self):
        with self.lock:
            self.auto_restart = False
            if self.proc and self.proc.poll() is None:
                self.proc.terminate()
                try:
                    self.proc.wait(timeout=5)
                except Exception:
                    self.proc.kill()
            self.proc = None
        return self.status()

    def restart_server(self):
        self.stop_server()
        return self.start_server()

    def _ensure_watchdog(self):
        if self.watchdog_started:
            return
        self.watchdog_started = True

        def _watch():
            while True:
                time.sleep(3)
                with self.lock:
                    p = self.proc
                    ar = self.auto_restart
                if p is None or not ar:
                    continue
                rc = p.poll()
                if rc is None:
                    continue
                # 进程异常退出 → 自动重启（带崩溃次数上限，避免无限重启循环）
                with self.lock:
                    self.proc = None
                if ar and self.crash_count < self.MAX_AUTO_RESTARTS:
                    self.crash_count += 1
                    self.restart_count += 1
                    time.sleep(3)
                    try:
                        self._spawn()
                    except Exception:
                        pass
                else:
                    with self.lock:
                        self.auto_restart = False

        t = threading.Thread(target=_watch, daemon=True)
        t.start()

    def query_status(self):
        try:
            with urllib.request.urlopen(
                f'http://127.0.0.1:{self.port}/api/status', timeout=2
            ) as r:
                return json.loads(r.read().decode('utf-8'))
        except Exception:
            return None

    def status(self):
        alive = self.proc is not None and self.proc.poll() is None
        srv = self.query_status() if alive else None
        uptime = int(time.time() - self.start_time) if (alive and self.start_time) else 0
        online = srv.get('online', 0) if srv else 0
        return {
            'running': alive,
            'online': online,
            'port': self.port,
            'uptime': uptime,
            'version': 'py-flask',
            'lan_url': f'http://{get_local_ip()}:{self.port}',
            'db_mode': srv.get('db_mode') if srv else None,
            'crashed': (not alive and self.crash_count >= self.MAX_AUTO_RESTARTS),
            'port_conflict': self.port_conflict,
            'restarts': self.restart_count,
        }

    def open_chat(self):
        webbrowser.open(f'http://127.0.0.1:{self.port}')
        return {'ok': True}

    # ---- 网络安全（IP 展示 / 拉黑 / 白名单锁定）----
    def _sec(self, action, **params):
        try:
            q = urllib.parse.urlencode({'secret': self.secret, **params})
            with urllib.request.urlopen(
                f'http://127.0.0.1:{self.port}/api/security/{action}?{q}', timeout=3
            ) as r:
                return json.loads(r.read().decode('utf-8'))
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def security_info(self):
        return self._sec('info')

    def block_ip(self, ip):
        return self._sec('block', ip=ip)

    def unblock_ip(self, ip):
        return self._sec('unblock', ip=ip)

    def set_lock(self, on):
        return self._sec('lock', on='1' if on else '0')

    def allow_ip(self, ip):
        return self._sec('allow', ip=ip)

    def unallow_ip(self, ip):
        return self._sec('unallow', ip=ip)


# ---------------------------------------------------------------------------
# JS 桥：暴露给控制面板 HTML 调用
# ---------------------------------------------------------------------------
class Api:
    def __init__(self, ctrl):
        self.ctrl = ctrl

    def get_status(self):
        return self.ctrl.status()

    def start_server(self):
        return self.ctrl.start_server()

    def stop_server(self):
        return self.ctrl.stop_server()

    def restart_server(self):
        return self.ctrl.restart_server()

    def open_chat(self):
        return self.ctrl.open_chat()

    def set_clipboard(self, text):
        """写入本机系统剪贴板（聊天 iframe 的剪贴板同步功能经 postMessage 转发至此）。"""
        return set_clipboard(text)

    def get_auto_secret(self):
        """返回 GUI 自动登录 secret（前端拼 iframe URL 用）。"""
        return {'secret': self.ctrl.secret}

    def security_info(self):
        return self.ctrl.security_info()

    def block_ip(self, ip):
        return self.ctrl.block_ip(ip)

    def unblock_ip(self, ip):
        return self.ctrl.unblock_ip(ip)

    def set_lock(self, on):
        return self.ctrl.set_lock(on)

    def allow_ip(self, ip):
        return self.ctrl.allow_ip(ip)

    def unallow_ip(self, ip):
        return self.ctrl.unallow_ip(ip)

    def refresh(self):
        return self.ctrl.status()

    def restart(self):
        """聊天页「重启应用」按钮：重启本地服务进程。

        之前 Api 里没有 restart 方法，前端 `window.pywebview.api.restart`
        取不到，会退回 `location.reload()` 硬刷整页——页面被销毁时 WebSocket
        来不及正常关闭，服务端 handler 线程仍阻塞在 ws.receive() 上，旧连接
        变成僵死连接挂在在线列表里，新连接又建不起来，表现为重启后假死。
        这里改为走正规的服务重启流程。
        """
        r = self.ctrl.restart_server()
        return {'ok': True, 'status': r}


# ---------------------------------------------------------------------------
# 控制面板 HTML（内嵌，便于打包，无需额外数据文件）
# ---------------------------------------------------------------------------
PANEL_HTML = r"""
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>聊天室 · 控制中心</title>
<style>
  :root{--bg:#0f1115;--card:#1a1d24;--fg:#e6e6e6;--muted:#9aa0aa;--accent:#2f81f7;--green:#3fb950;--red:#f85149;--amber:#d29922;}
  *{box-sizing:border-box}
  html,body{height:100%;margin:0}
  body{font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,"PingFang SC","Microsoft YaHei",sans-serif;background:var(--bg);color:var(--fg);display:flex;flex-direction:column;overflow:hidden}
  .tabs{flex:0 0 auto;display:flex;border-bottom:1px solid #2a2f37;background:var(--bg)}
  .tab{flex:1;border:0;background:transparent;color:var(--muted);font-size:15px;font-weight:600;padding:14px 0;cursor:pointer;border-bottom:2px solid transparent}
  .tab.active{color:var(--fg);border-bottom-color:var(--accent)}
  #views{flex:1 1 auto;position:relative;min-height:0}
  #panelView,#chatView{position:absolute;inset:0;overflow:auto}
  #chatView{display:none}
  #chatView #chatMount{height:100%;overflow:auto;background:#fff}
  #chatView #chat-container{width:100%!important;height:100%!important;border-radius:0!important;box-shadow:none!important}
  #chatOverlay{position:absolute;inset:0;display:none;align-items:center;justify-content:center;color:var(--muted);font-size:14px;background:var(--bg);text-align:center;padding:20px}
  .panel-inner{padding:20px}
  h1{font-size:18px;margin:0 0 4px}
  .sub{color:var(--muted);font-size:12px;margin-bottom:18px}
  .card{background:var(--card);border-radius:12px;padding:16px;margin-bottom:14px}
  .status-row{display:flex;align-items:center;gap:10px}
  .dot{width:12px;height:12px;border-radius:50%;background:var(--red);box-shadow:0 0 8px var(--red)}
  .dot.on{background:var(--green);box-shadow:0 0 8px var(--green)}
  .status-text{font-size:16px;font-weight:600}
  .grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:14px}
  .metric{background:var(--bg);border-radius:10px;padding:12px}
  .metric .label{color:var(--muted);font-size:12px}
  .metric .value{font-size:22px;font-weight:700;margin-top:4px}
  .metric .value.big{color:var(--accent)}
  .url{font-size:12px;color:var(--muted);word-break:break-all;margin-top:6px}
  .btns{display:flex;flex-wrap:wrap;gap:10px;margin-top:6px}
  button{flex:1;min-width:90px;border:0;border-radius:10px;padding:11px 12px;font-size:14px;font-weight:600;cursor:pointer;color:#fff;background:var(--accent);transition:filter .15s}
  button:hover{filter:brightness(1.1)}
  button.secondary{background:#30363d}
  button.warn{background:var(--amber)}
  button.danger{background:var(--red)}
  button:disabled{opacity:.5;cursor:not-allowed}
  .toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);max-width:min(520px,80vw);max-height:30vh;overflow-y:auto;background:#000d;padding:10px 16px;border-radius:10px;font-size:13px;line-height:1.5;word-break:break-word;opacity:0;transition:opacity .25s;pointer-events:none;z-index:4000}
  .toast.show{opacity:1}
  .foot{color:var(--muted);font-size:11px;margin-top:8px;text-align:center}
  .sec-title{font-size:13px;font-weight:700;margin-bottom:4px}
  .sec-label{color:var(--muted);font-size:11px;margin:10px 0 4px}
  .sec-list{font-size:12px;color:var(--fg);display:flex;flex-direction:column;gap:4px}
  .sec-item{display:flex;align-items:center;gap:8px;background:var(--bg);border-radius:8px;padding:5px 8px}
  .sec-item .u{flex:1;word-break:break-all}
  .sec-item button{flex:0 0 auto;min-width:0;padding:3px 10px;font-size:11px;border:0;border-radius:8px;cursor:pointer;color:#fff;background:#30363d}
  .sec-item button.danger{background:var(--red)}
  .sec-row{display:flex;align-items:center;gap:8px;margin-top:10px}
  .sec-input{flex:1;background:var(--bg);border:1px solid #30363d;border-radius:8px;color:var(--fg);padding:7px 10px;font-size:12px}
  .sec-hint{color:var(--muted);font-size:11px;flex:1}
  /* 连接与安全：子区块分组，风格统一 */
  .sec-block{background:#12141a;border:1px solid #262b33;border-radius:10px;padding:12px;margin-top:10px}
  .sec-block .sec-label{margin-top:0}
  .sec-actions{display:flex;gap:6px}
  .sec-actions button{flex:0 0 auto;min-width:0;padding:3px 10px;font-size:11px;border:0;border-radius:8px;cursor:pointer;color:#fff;background:#30363d}
  .sec-actions button.allow{background:#238636}
  .sec-actions button.danger{background:var(--red)}
  .sec-head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:14px}
  .sec-head .sec-label{margin:0}
</style>
</head>
<body>
  <div class="tabs">
    <button id="tabPanel" class="tab active" onclick="showTab('panel')">控制面板</button>
    <button id="tabChat" class="tab" onclick="showTab('chat')">聊天</button>
  </div>
  <div id="views">
    <div id="panelView">
      <div class="panel-inner">
        <h1>聊天室 · 控制中心</h1>
        <div class="sub">服务运行状态由本面板统一监控，异常会自动重启</div>

        <div class="card">
          <div class="status-row">
            <span id="dot" class="dot"></span>
            <span id="statusText" class="status-text">检测中…</span>
          </div>
          <div class="grid">
            <div class="metric"><div class="label">在线人数</div><div id="online" class="value big">0</div></div>
            <div class="metric"><div class="label">运行时长</div><div id="uptime" class="value">0s</div></div>
          </div>
          <div class="url">本机访问：<span id="localUrl">—</span></div>
          <div class="url">局域网分享：<span id="lanUrl">—</span></div>
        </div>

        <div class="card">
          <div class="btns">
            <button id="btnStart" class="secondary" onclick="doStart()">启动</button>
            <button id="btnStop" class="danger" onclick="doStop()">关闭</button>
            <button id="btnRestart" class="warn" onclick="doRestart()">重启</button>
            <button id="btnRefresh" class="secondary" onclick="doRefresh()">刷新</button>
            <button id="btnOpen" onclick="doOpen()">打开聊天室</button>
          </div>
        </div>

        <div class="card">
          <div class="sec-title">连接与安全</div>

          <div class="sec-label">在线连接（SYSTEM 系统账号不可拉黑）</div>
          <div id="secOnline" class="sec-list">—</div>

          <div class="sec-block">
            <div class="sec-head">
              <div class="sec-label">黑名单 IP</div>
              <button id="btnLock" class="warn" onclick="toggleLock()" style="flex:0 0 auto;min-width:84px;font-size:11px;padding:4px 10px">开启锁定</button>
            </div>
            <div id="secBlocked" class="sec-list">—</div>
            <div class="sec-hint" style="margin-top:6px">开启锁定后仅白名单 IP 可加入，实现 1-1 专发</div>
            <div class="sec-label" style="margin-top:12px">白名单 IP</div>
            <div id="secAllowed" class="sec-list">—</div>
            <div class="sec-row">
              <input id="allowedInput" class="sec-input" placeholder="输入 IP，如 192.168.1.100">
              <button class="secondary" onclick="addAllowed()" style="flex:0 0 auto">添加</button>
            </div>
          </div>
        </div>

        <div class="foot" id="foot">端口 — · 版本 py-flask</div>
      </div>
    </div>

    <div id="chatView">
      <div id="chatMount"></div>
      <div id="chatOverlay">服务未启动，请先在「控制面板」点击启动</div>
    </div>
  </div>

  <div class="toast" id="toast"></div>

<script>
  let api=null, refreshTimer=null, uptimeTimer=null, chatLoaded=false;
  let uiRunning=false, uiUptimeBase=0, uiUptimeTs=0, uiPort=0;
  function showToast(m){const t=document.getElementById('toast');t.textContent=m;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),1800);}
  function fmtUptime(s){if(!s)return '0s';const h=Math.floor(s/3600),m=Math.floor((s%3600)/60),ss=s%60;if(h)return h+'h'+m+'m';if(m)return m+'m'+ss+'s';return ss+'s';}
  function showTab(which){
    const p=document.getElementById('panelView'),c=document.getElementById('chatView');
    if(which==='chat'){p.style.display='none';c.style.display='block';document.getElementById('tabChat').classList.add('active');document.getElementById('tabPanel').classList.remove('active');}
    else{p.style.display='block';c.style.display='none';document.getElementById('tabPanel').classList.add('active');document.getElementById('tabChat').classList.remove('active');}
  }
  function setRunning(on){const d=document.getElementById('dot');d.className='dot'+(on?' on':'');document.getElementById('statusText').textContent=on?'运行中':'已停止';document.getElementById('btnStart').disabled=on;document.getElementById('btnStop').disabled=!on;document.getElementById('btnOpen').disabled=!on;uiRunning=on;if(!on){uiUptimeBase=0;uiUptimeTs=0;document.getElementById('uptime').textContent='0s';document.getElementById('chatOverlay').style.display='flex';}}
  // 桌面端适配：不再用 iframe 外链，而是把聊天网页（node/public/index.html）
  // 运行时拉取并直接注入主文档，消除双层滚动/全屏/剪贴板跨文档等问题。
  async function loadChat(){
    if(!uiPort||chatLoaded)return;
    chatLoaded=true;
    const mount=document.getElementById('chatMount');
    const ov=document.getElementById('chatOverlay');
    try{
      const resp=await fetch('http://127.0.0.1:'+uiPort+'/');
      if(!resp.ok)throw new Error('HTTP '+resp.status);
      const html=await resp.text();
      const doc=new DOMParser().parseFromString(html,'text/html');
      // base href：html= 注入的页面无 HTTP 来源，所有 /api /sounds 等绝对路径据此解析
      const base=document.createElement('base');
      base.href='http://127.0.0.1:'+uiPort+'/';
      document.head.appendChild(base);
      // 注入聊天页样式（剔除 body 布局规则，避免污染控制台；容器已用 CSS 铺满）
      let styleText=Array.from(doc.querySelectorAll('style')).map(s=>s.textContent).join('\n');
      styleText=styleText.replace(/body\s*\{[^}]*\}/g,'');
      const st=document.createElement('style');st.textContent=styleText;document.head.appendChild(st);
      // 注入聊天页 DOM（跳过 <script>，稍后统一重建执行）
      Array.from(doc.body.childNodes).forEach(n=>{
        if(n.nodeType===1&&n.tagName==='SCRIPT')return;
        mount.appendChild(n);
      });
      // 抽取脚本：去掉 ServiceWorker 注册（桌面不需要 PWA），保留其后心跳逻辑；
      // 整体包进 IIFE 隔离全局，避免与控制台同名变量/函数冲突
      let scripts=Array.from(doc.querySelectorAll('script')).map(s=>s.textContent).join('\n;\n');
      scripts=scripts.replace(/navigator\.serviceWorker\.register\('\/sw\.js'\)/g,"Promise.resolve()");
      // 内嵌后 location.host 指向 pywebview 假 host，WebSocket 地址需固定到本地服务
      // 注意：原代码是模板字符串 `...${protocol}//${location.host}...`，需连同外层反引号一起替换，
      // 否则会留下 ` 'ws://...' `（带单引号的模板字符串），导致 WebSocket 地址非法、连不上。
      scripts=scripts.replace(/`\$\{protocol\}\/\/\$\{location\.host\}`/g,"('ws://127.0.0.1:"+uiPort+"')");
      // 自动登录：内嵌页没有 URL 参数，直接改写读参逻辑为写死的 auto=1&secret
      let secret='';
      try{const r=await api.get_auto_secret();if(r&&r.secret)secret=r.secret;}catch(e){}
      scripts=scripts.replace(/new URLSearchParams\(location\.search\)/g,"new URLSearchParams('auto=1&secret="+encodeURIComponent(secret)+"')");
      const wrap=document.createElement('script');
      wrap.textContent='(function(){\n'+scripts+'\n})();';
      document.body.appendChild(wrap);
      if(ov)ov.style.display='none';
    }catch(e){
      if(ov){ov.style.display='flex';ov.textContent='聊天页面加载失败：'+(e&&e.message||e);}
    }
  }
  function update(s){if(!s)return;uiPort=s.port||uiPort;setRunning(s.running);document.getElementById('online').textContent=s.online!=null?s.online:0;if(s.running){uiUptimeBase=s.uptime||0;uiUptimeTs=Date.now();document.getElementById('uptime').textContent=fmtUptime(uiUptimeBase);document.getElementById('chatOverlay').style.display='none';loadChat();}document.getElementById('localUrl').textContent='http://127.0.0.1:'+(s.port||'—');document.getElementById('lanUrl').textContent=s.lan_url||'—';let foot='端口 '+(s.port||'—')+' · 版本 '+(s.version||'py-flask')+(s.db_mode?(' · DB:'+s.db_mode):'')+(s.restarts?(' · 服务已自动重启 '+s.restarts+' 次，原因见 data/server.log'):'')+(s.crashed?' · 服务反复崩溃，请查看日志':'');if(s.port_conflict){foot+=' ⚠️ 检测到默认端口 3001 被其他服务占用，已改用 '+s.port+'；若消息收不到，请关闭其他 聊天室/服务 实例后重启本程序';}document.getElementById('foot').textContent=foot;}
  // 运行时长前端独立计时：每秒平滑增长，不再依赖刷新周期是否触发
  function tickUptime(){if(uiRunning){const elapsed=Math.floor((Date.now()-uiUptimeTs)/1000);document.getElementById('uptime').textContent=fmtUptime(uiUptimeBase+elapsed);}}
  async function refresh(){try{update(await api.get_status());}catch(e){/* 忽略瞬时读取失败 */}}
  async function doStart(){try{update(await api.start_server());showToast('已启动');}catch(e){showToast('启动失败');}}
  async function doStop(){try{update(await api.stop_server());showToast('已关闭');}catch(e){showToast('关闭失败');}}
  async function doRestart(){try{update(await api.restart_server());showToast('已重启');}catch(e){showToast('重启失败');}}
  async function doRefresh(){await refresh();showToast('已刷新');}
  async function doOpen(){try{await api.open_chat();}catch(e){}}
  // ---- 连接与安全：在线 IP / 拉黑 / 白名单锁定 ----
  // 独立 5s 轮询（不并入 3s 状态刷新），避免与消息链路抢主线程
  let secLocked=false, secTimer=null;
  function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
  const SYS_USER='sys';
  function renderSec(d){
    if(!d||!d.ok){document.getElementById('secOnline').textContent='服务未启动或无权限';return;}
    const online=d.online||[];
    document.getElementById('secOnline').innerHTML=online.length?online.map(o=>{
      const isSys=o.user===SYS_USER;  // SYSTEM 系统账号：不可拉黑、不可加白名单
      const actions=isSys?'<span class="sec-actions"><span style="color:var(--muted);font-size:11px">系统账号</span></span>'
        :'<span class="sec-actions"><button class="allow" onclick="allowIp(\''+esc(o.ip)+'\')">白名单</button><button class="danger" onclick="blockIp(\''+esc(o.ip)+'\')">拉黑</button></span>';
      return '<div class="sec-item"><span class="u">'+esc(o.user)+' ('+esc(o.ip)+')</span>'+actions+'</div>';
    }).join(''):'<span>无在线连接</span>';
    document.getElementById('secBlocked').innerHTML=(d.blocked_ips||[]).length?d.blocked_ips.map(ip=>'<div class="sec-item"><span class="u">'+esc(ip)+'</span><button onclick="unblockIp(\''+esc(ip)+'\')">解除拉黑</button></div>').join(''):'<span>无</span>';
    document.getElementById('secAllowed').innerHTML=(d.allowed_ips||[]).length?d.allowed_ips.map(ip=>'<div class="sec-item"><span class="u">'+esc(ip)+'</span><button onclick="unallowIp(\''+esc(ip)+'\')">移除</button></div>').join(''):'<span>无</span>';
    secLocked=!!d.locked;
    const bl=document.getElementById('btnLock');
    bl.textContent=secLocked?'关闭锁定':'开启锁定';
    bl.className=secLocked?'danger':'warn';
  }
  async function refreshSec(){try{renderSec(await api.security_info());}catch(e){}}
  async function blockIp(ip){if(ip===SYS_USER){showToast('系统账号不可拉黑');return;}try{renderSec(await api.block_ip(ip));showToast('已拉黑 '+ip);}catch(e){showToast('拉黑失败');}}
  async function allowIp(ip){try{renderSec(await api.allow_ip(ip));showToast('已加入白名单 '+ip);}catch(e){showToast('操作失败');}}
  async function unblockIp(ip){try{renderSec(await api.unblock_ip(ip));showToast('已解除拉黑 '+ip);}catch(e){showToast('操作失败');}}
  async function toggleLock(){try{const r=await api.set_lock(!secLocked);renderSec(r);showToast(r.locked?'已开启锁定（仅白名单 IP 可加入）':'已关闭锁定');}catch(e){showToast('操作失败');}}
  async function addAllowed(){const inp=document.getElementById('allowedInput');const ip=(inp.value||'').trim();if(!ip){showToast('请输入 IP');return;}try{renderSec(await api.allow_ip(ip));inp.value='';showToast('已添加白名单 '+ip);}catch(e){showToast('操作失败');}}
  async function unallowIp(ip){try{renderSec(await api.unallow_ip(ip));showToast('已移除 '+ip);}catch(e){showToast('操作失败');}}
  function ensureTimers(){if(refreshTimer)clearInterval(refreshTimer);refreshTimer=setInterval(refresh,3000);if(uptimeTimer)clearInterval(uptimeTimer);uptimeTimer=setInterval(tickUptime,1000);if(secTimer)clearInterval(secTimer);secTimer=setInterval(refreshSec,5000);refreshSec();}
  function onReady(){
    if(api)return;
    const pw=window.pywebview;
    api=(pw&&pw.api)?pw.api:null;
    if(!api){setTimeout(onReady,80);return;}
    refresh();ensureTimers();
  }
  if(window.addEventListener){window.addEventListener('pywebviewready',function(){api=window.pywebview.api;refresh();ensureTimers();});}
  onReady();
  // 聊天 iframe 剪贴板同步：收到 {type:'sync_clipboard',text} 后经桥写入系统剪贴板
  if(window.addEventListener){
    window.addEventListener('message',(e)=>{
      const d=e.data;
      if(d&&d.type==='sync_clipboard'&&d.text&&window.pywebview&&window.pywebview.api){
        try{window.pywebview.api.set_clipboard(String(d.text));}catch(err){}
      }
    });
  }
</script>
</body>
</html>
"""


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
def main():
    # 让内置 WebView2 窗口直连本地服务，绕过系统代理 / VPN 对 127.0.0.1
    # 的拦截（系统浏览器默认“绕过代理访问本地地址”，但 WebView2 不保证开启）。
    # WebView2 官方支持通过环境变量注入额外浏览器参数；--no-proxy-server 等价于
    # 强制直连，使内置聊天区的 fetch / WebSocket 都能稳定连上本机服务。
    # 同时禁用后台节流/冻结：Windows 节能模式与 Chromium 会对不可见/失焦页面
    # 暂停 JS 定时器并延迟 WebSocket 消息派发——这正是“挂后台后收不到消息、
    # 剪贴板同步失效”的根因，这些参数让内置聊天窗口在后台也保持实时收发。
    _wv_args = os.environ.get('WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS', '')
    _wv_extra = [
        '--no-proxy-server',
        '--disable-background-timer-throttling',
        '--disable-renderer-backgrounding',
        '--disable-backgrounding-occluded-windows',
        '--disable-background-network-throttling',
    ]
    for _a in _wv_extra:
        if _a not in _wv_args:
            _wv_args = (_wv_args + ' ' + _a).strip()
    os.environ['WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS'] = _wv_args

    # 服务模式：作为子进程自举（冻结 exe 或显式 --server 时进入）
    if '--server' in sys.argv:
        port = int(os.getenv('PORT', '3001'))
        secret = None
        for i, a in enumerate(sys.argv):
            if a == '--port' and i + 1 < len(sys.argv):
                try:
                    port = int(sys.argv[i + 1])
                except ValueError:
                    pass
            if a == '--secret' and i + 1 < len(sys.argv):
                secret = sys.argv[i + 1] or None
        run_server_mode(port, secret)
        return

    # 控制中心模式（默认）
    ctrl = Controller()
    ctrl.start_server()

    import webview
    api = Api(ctrl)
    webview.create_window(
        '聊天室 · 控制中心',
        html=PANEL_HTML,
        js_api=api,
        width=900,
        height=720,
    )
    webview.start()

    # 关闭窗口时清理服务进程
    ctrl.stop_server()
    os._exit(0)


if __name__ == '__main__':
    main()
