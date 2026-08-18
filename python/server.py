#!/usr/bin/env python
'''Portable Python version of the IM server.

Key points:
- Serves the existing front-end files from the 'public' folder.
- Implements the same HTTP API routes as the original Node server.
- Provides a plain WebSocket endpoint (compatible with the client code).
- Uses SQLite (Python builtin) for persistence – identical schema.
- All paths are resolved relative to the project root, making the whole 'im' directory movable.

Run via the provided start.bat (which calls run.py) or directly with 'python py/server.py'.
'''

import os
import sys
import hashlib
import secrets
import sqlite3
import datetime
import json
import threading
import socket
import time
from flask import Flask, request, jsonify, send_from_directory
from flask_sock import Sock
from werkzeug.utils import secure_filename

# Configuration
# - 源码模式：所有路径相对项目根目录（可整体移动）。
# - 打包模式（PyInstaller --onefile）：静态资源(public)在打包 bundle(sys._MEIPASS) 中，
#   而数据库/uploads 放在 exe 同目录下，既能持久化又在移动时保持便携。
if getattr(sys, 'frozen', False):
    _RES = sys._MEIPASS
    _DATA = os.path.dirname(sys.executable)
    PUBLIC_DIR = os.path.join(_RES, 'public')          # 打包时 --add-data "node/public;public"
else:
    _ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
    _RES = _ROOT
    _DATA = _ROOT
    PUBLIC_DIR = os.path.join(_ROOT, 'node', 'public') # 前端归入 node/ 目录

DATA_DIR = os.path.join(_DATA, 'data')
UPLOADS_DIR = os.path.join(_DATA, 'uploads')
DB_PATH = os.path.join(DATA_DIR, 'chat.db')

# Ensure required directories exist
os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(UPLOADS_DIR, exist_ok=True)

app = Flask(__name__, static_folder=PUBLIC_DIR, static_url_path='')
sock = Sock(app)


# ---------------------------------------------------------------------------
# CORS：GUI 桌面版把聊天页内嵌进 pywebview 主文档（非 http 来源）后，
# 所有 fetch('/api/...') 相对/绝对请求都会变成跨源，需放开跨域才能正常收发。
# ---------------------------------------------------------------------------
@app.after_request
def _add_cors_headers(resp):
    resp.headers['Access-Control-Allow-Origin'] = '*'
    resp.headers['Access-Control-Allow-Methods'] = 'GET, POST, PUT, DELETE, OPTIONS'
    resp.headers['Access-Control-Allow-Headers'] = 'Content-Type, Authorization'
    return resp

# Database helpers
_db = None  # module-level SQLite connection

def get_db():
    """Return a module‑level SQLite connection."""
    global _db
    if _db is None:
        _db = sqlite3.connect(DB_PATH, detect_types=sqlite3.PARSE_DECLTYPES, check_same_thread=False)
        _db.row_factory = sqlite3.Row
        # 启用 WAL + busy_timeout，显著降低并发写入时的 "database is locked"，
        # 这是 Py 版“突然失去服务”的主要诱因之一。
        try:
            _db.execute('PRAGMA journal_mode=WAL')
            _db.execute('PRAGMA busy_timeout=5000')
            _db.execute('PRAGMA synchronous=NORMAL')
        except Exception:
            pass
    return _db








def init_db():
    db = get_db()
    # Users table
    db.executescript('''
        CREATE TABLE IF NOT EXISTS users (
            username TEXT PRIMARY KEY,
            password_hash TEXT NOT NULL,
            token TEXT NOT NULL
        );
    ''')
    # Messages table (matches the Node version exactly)
    db.executescript('''
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user TEXT NOT NULL,
            text TEXT,
            type TEXT NOT NULL DEFAULT 'text',
            image_url TEXT,
            file_name TEXT,
            file_size INTEGER,
            reply_to INTEGER,
            created_at TEXT NOT NULL
        );
    ''')
    db.commit()

# Utility functions
def hash_password(pw: str) -> str:
    return hashlib.sha256(pw.encode('utf-8')).hexdigest()

def generate_token() -> str:
    return secrets.token_hex(16)

def local_timestamp() -> str:
    now = datetime.datetime.now()
    return now.strftime('%Y-%m-%dT%H:%M:%S')

# In‑memory state
online_users = set()          # usernames currently online
user_sockets = {}              # username -> set of websocket objects (支持多设备同账号在线)
socket_lock = threading.RLock()  # protect the above structures (reentrant: heartbeat + handler may nest)

# HTTP API routes
@app.route('/api/register', methods=['POST'])
def api_register():
    data = request.get_json(force=True)
    username = data.get('username')
    password = data.get('password')
    if not username or not password:
        return jsonify({'ok': False, 'reason': '用户名和密码不能为空'})
    db = get_db()
    exists = db.execute('SELECT username FROM users WHERE username = ?', (username,)).fetchone()
    if exists:
        return jsonify({'ok': False, 'reason': '用户名已存在'})
    token = generate_token()
    pw_hash = hash_password(password)
    db.execute('INSERT INTO users (username, password_hash, token) VALUES (?,?,?)', (username, pw_hash, token))
    db.commit()
    return jsonify({'ok': True, 'token': token, 'username': username})

@app.route('/api/login', methods=['POST'])
def api_login():
    data = request.get_json(force=True)
    username = data.get('username')
    password = data.get('password')
    if not username or not password:
        return jsonify({'ok': False, 'reason': '用户名和密码不能为空'})
    db = get_db()
    user = db.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()
    if not user:
        return jsonify({'ok': False, 'reason': '用户不存在'})
    if user['password_hash'] != hash_password(password):
        return jsonify({'ok': False, 'reason': '密码错误'})
    token = generate_token()
    db.execute('UPDATE users SET token = ? WHERE username = ?', (token, username))
    db.commit()
    return jsonify({'ok': True, 'token': token, 'username': username})

@app.route('/api/history/dates', methods=['GET'])
def api_history_dates():
    db = get_db()
    rows = db.execute('SELECT DISTINCT date(created_at) as d FROM messages ORDER BY d DESC').fetchall()
    dates = [row['d'] for row in rows]
    return jsonify({'dates': dates})

@app.route('/api/history/messages', methods=['GET'])
def api_history_messages():
    date = request.args.get('date')
    if not date:
        return jsonify({'messages': []})
    db = get_db()
    msgs = db.execute('SELECT * FROM messages WHERE date(created_at) = ? ORDER BY id', (date,)).fetchall()
    return jsonify({'messages': [dict(row) for row in msgs]})

@app.route('/api/search', methods=['GET'])
def api_search():
    q = request.args.get('q')
    if not q:
        return jsonify({'messages': []})
    pattern = f'%{q}%'
    db = get_db()
    msgs = db.execute('SELECT * FROM messages WHERE text LIKE ? ORDER BY id', (pattern,)).fetchall()
    return jsonify({'messages': [dict(row) for row in msgs]})


@app.route('/api/status', methods=['GET'])
def api_status():
    """供控制中心查询服务存活状态与在线人数（无需鉴权）。"""
    mode = 'default'
    try:
        row = get_db().execute('PRAGMA journal_mode').fetchone()
        if row and str(row[0]).lower() == 'wal':
            mode = 'wal'
    except Exception:
        pass
    return jsonify({
        'ok': True,
        'running': True,
        'online': len(online_users),
        'version': 'py-flask',
        'db_mode': mode,
    })

# File upload
@app.route('/upload', methods=['POST'])
def upload_file():
    if 'file' not in request.files:
        return jsonify({'error': 'No file part'}), 400
    file = request.files['file']
    if file.filename == '':
        return jsonify({'error': 'No selected file'}), 400
    # Save with a random filename to avoid collisions (same as multer's default)
    filename = secrets.token_hex(8)
    ext = os.path.splitext(secure_filename(file.filename))[1]
    saved_name = filename + ext
    dest_path = os.path.join(UPLOADS_DIR, saved_name)
    file.save(dest_path)
    url = f'/uploads/{saved_name}'
    return jsonify({'url': url, 'name': file.filename, 'size': os.path.getsize(dest_path)})

# Broadcast helper
def safe_send(ws, payload):
    """线程安全的单点发送；返回是否成功。"""
    try:
        with socket_lock:
            ws.send(json.dumps(payload))
        return True
    except Exception:
        return False


def broadcast(payload: dict):
    with socket_lock:
        targets = [ws for socks in user_sockets.values() for ws in socks]
    for ws in targets:
        safe_send(ws, payload)


def start_heartbeat(interval=30):
    """Periodically ping every connected client so idle WebSocket connections
    stay alive and wedged ones are detected (client replies with pong).
    发送失败的连接视为已死，立即清理并广播最新在线列表。"""
    def _loop():
        while True:
            time.sleep(interval)
            with socket_lock:
                dead = []
                for username, socks in list(user_sockets.items()):
                    for ws in list(socks):
                        try:
                            ws.send(json.dumps({'type': 'ping'}))
                        except Exception:
                            dead.append((username, ws))
                for username, ws in dead:
                    socks = user_sockets.get(username)
                    if socks:
                        socks.discard(ws)
                        if not socks:
                            user_sockets.pop(username, None)
                    online_users.discard(username)
                    user_ip.pop(username, None)
            if dead:
                broadcast({'type': 'online_users', 'users': list(online_users)})
    t = threading.Thread(target=_loop, daemon=True)
    t.start()


# WebSocket endpoint
@sock.route('/')
def websocket_route(ws):
    registered_user = None
    try:
        while True:
            raw = ws.receive()
            if raw is None:
                break
            try:
                msg = json.loads(raw)
            except Exception:
                continue
            # Ignore keepalive ack / stray ping echoes from the client.
            if msg.get('type') in ('ping', 'pong'):
                continue
            # Registration
            if msg.get('type') == 'register':
                client_ip = request.remote_addr or ''
                # 网络安全：IP 黑名单 / 白名单锁定（仅允许指定 IP 加入，1-1 专发）
                if client_ip in blocked_ips:
                    safe_send(ws, {'type': 'error', 'reason': '您的 IP 已被拉黑，无法加入聊天'})
                    ws.close()
                    break
                if locked and client_ip not in allowed_ips:
                    safe_send(ws, {'type': 'error', 'reason': '系统已锁定，仅允许指定 IP 加入聊天'})
                    ws.close()
                    break
                username = msg.get('user')
                token = msg.get('token')
                db = get_db()
                user = db.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()
                if user and user['token'] == token:
                    with socket_lock:
                        # 支持同一账号多设备同时在线（刷新/多端不会互踢）
                        user_sockets.setdefault(username, set()).add(ws)
                        already_online = username in online_users
                        online_users.add(username)
                        user_ip[username] = client_ip
                        registered_user = username
                    safe_send(ws, {'type': 'registered', 'ok': True})
                    # Send today's history
                    today_str = datetime.datetime.now().strftime('%Y-%m-%d')
                    msgs = db.execute('SELECT * FROM messages WHERE date(created_at) = ? ORDER BY id', (today_str,)).fetchall()
                    safe_send(ws, {'type': 'history', 'messages': [dict(row) for row in msgs]})
                    # Broadcast online users
                    broadcast({'type': 'online_users', 'users': list(online_users)})
                    # System join if first login
                    if not already_online:
                        text = f'{username} 加入了群聊'
                        created = local_timestamp()
                        cur = db.execute('INSERT INTO messages (user, text, type, image_url, file_name, file_size, reply_to, created_at) VALUES (?,?,?,?,?,?,?,?)', (username, text, 'system_join', None, None, None, None, created))
                        db.commit()
                        saved = db.execute('SELECT * FROM messages WHERE id = ?', (cur.lastrowid,)).fetchone()
                        broadcast(dict(saved))
                else:
                    safe_send(ws, {'type': 'registered', 'ok': False, 'reason': '身份验证失败'})
                    ws.close()
                    break
                continue
            # Require registration for other messages
            if not registered_user:
                safe_send(ws, {'type': 'error', 'reason': '请先登录'})
                continue
            # Normal chat message
            try:
                created_at = local_timestamp()
                db = get_db()
                insert_stmt = 'INSERT INTO messages (user, text, type, image_url, file_name, file_size, reply_to, created_at) VALUES (?,?,?,?,?,?,?,?)'
                params = (registered_user, msg.get('text'), msg.get('type', 'text'), msg.get('image_url'), msg.get('file_name'), msg.get('file_size'), msg.get('reply_to'), created_at)
                cur = db.execute(insert_stmt, params)
                db.commit()
                saved_row = db.execute('SELECT * FROM messages WHERE id = ?', (cur.lastrowid,)).fetchone()
                saved = dict(saved_row)
                # Enrich with reply meta if needed
                if saved.get('reply_to'):
                    replied = db.execute('SELECT * FROM messages WHERE id = ?', (saved['reply_to'],)).fetchone()
                    if replied:
                        saved['reply_to_user'] = replied['user']
                        if replied['text']:
                            saved['reply_to_text'] = replied['text']
                        else:
                            saved['reply_to_text'] = '[图片]' if replied['type'] == 'image' else '[文件]'
                # Preserve client ID for ack handling
                if '_clientId' in msg:
                    saved['_clientId'] = msg['_clientId']
                broadcast(saved)
            except Exception as e:
                # A transient DB error must not kill the connection handler.
                print('[WS] failed to persist message:', e)
    finally:
        # Cleanup on disconnect
        with socket_lock:
            if registered_user:
                socks = user_sockets.get(registered_user)
                if socks:
                    socks.discard(ws)
                    if not socks:
                        user_sockets.pop(registered_user, None)
                online_users.discard(registered_user)
                user_ip.pop(registered_user, None)
                broadcast({'type': 'online_users', 'users': list(online_users)})
        try:
            ws.close()
        except Exception:
            pass

# Download endpoint: 附件方式下载并保留原始文件名（与 Express 版行为对齐）。
# 前端所有文件/图片消息的下载按钮都指向 /api/download?f=<存储名>&name=<原始名>。
@app.route('/api/download', methods=['GET'])
def api_download():
    f = os.path.basename(request.args.get('f', '') or '')
    name = request.args.get('name') or f or 'download'
    if not f:
        return jsonify({'error': 'invalid file'}), 400
    dest = os.path.join(UPLOADS_DIR, f)
    safe_root = os.path.abspath(UPLOADS_DIR)
    # 严格防止路径穿越（basename 已中和，此处为双保险）
    if not os.path.abspath(dest).startswith(safe_root + os.sep):
        return jsonify({'error': 'invalid path'}), 400
    if not os.path.isfile(dest):
        return jsonify({'error': 'file not found'}), 404
    # send_from_directory 内部 safe_join 再做一次防护；download_name 自动处理
    # 中文/特殊字符文件名（RFC 5987 filename*），Content-Type 由 mimetypes 推断。
    return send_from_directory(UPLOADS_DIR, f, as_attachment=True, download_name=name)

# Serve uploaded files
@app.route('/uploads/<path:filename>')
def serve_upload(filename):
    return send_from_directory(UPLOADS_DIR, filename)

@app.route('/')
def index():
    return send_from_directory(PUBLIC_DIR, 'index.html')

# Ensure admin account exists
def ensure_admin():
    db = get_db()
    admin_user = 'admin'
    admin_pass = 'admin123'  # default password; change as needed
    admin_hash = hash_password(admin_pass)
    exists = db.execute('SELECT username FROM users WHERE username = ?', (admin_user,)).fetchone()
    if not exists:
        token = generate_token()
        db.execute('INSERT INTO users (username, password_hash, token) VALUES (?,?,?)', (admin_user, admin_hash, token))
        db.commit()


# ---------------------------------------------------------------------------
# 内置系统账号 + 自动登录（GUI 打开即登录，无需手动输入）
# - SYS_USER / SYS_PASS：仅供 GUI 控制中心自动登录接收消息使用。
# - SERVER_SECRET：由 GUI 启动时随机生成并通过 --secret 传入服务子进程；
#   前端 iframe 只有拿到该 secret 才能调用 /api/auto_login，局域网其他
#   用户无法借此冒用系统账号。
# ---------------------------------------------------------------------------
SYS_USER = 'sys'
SYS_PASS = 'sys123'
SERVER_SECRET = None  # set at startup via --secret / run_server_mode(secret=...)


def ensure_system_user():
    db = get_db()
    exists = db.execute('SELECT username FROM users WHERE username = ?', (SYS_USER,)).fetchone()
    if not exists:
        db.execute('INSERT INTO users (username, password_hash, token) VALUES (?,?,?)',
                   (SYS_USER, hash_password(SYS_PASS), generate_token()))
        db.commit()


@app.route('/api/auto_login', methods=['GET'])
def api_auto_login():
    """GUI 自动登录：校验 secret 后签发系统账号 token（前端无需知道密码）。"""
    secret = request.args.get('secret', '')
    if not SERVER_SECRET or secret != SERVER_SECRET:
        return jsonify({'ok': False, 'reason': 'invalid secret'}), 403
    ensure_system_user()
    token = generate_token()
    db = get_db()
    db.execute('UPDATE users SET token = ? WHERE username = ?', (token, SYS_USER))
    db.commit()
    return jsonify({'ok': True, 'username': SYS_USER, 'token': token})


# ---------------------------------------------------------------------------
# 网络安全：在线连接 IP 展示、IP 黑名单、白名单锁定（1-1 专发）
# - 状态持久化在 data/security.json，重启后保留。
# - 管理接口统一校验 SERVER_SECRET，局域网其他用户无法直接操作。
# - 校验仅做内存 set 查找，开销极小，不影响消息收发即时性。
# ---------------------------------------------------------------------------
SECURITY_FILE = os.path.join(DATA_DIR, 'security.json')


def _load_security():
    try:
        with open(SECURITY_FILE, 'r', encoding='utf-8') as f:
            d = json.load(f)
    except Exception:
        d = {}
    return (
        set(d.get('blocked_ips', [])),
        set(d.get('allowed_ips', [])),
        bool(d.get('locked', False)),
    )


def _save_security():
    try:
        with open(SECURITY_FILE, 'w', encoding='utf-8') as f:
            json.dump({
                'blocked_ips': sorted(blocked_ips),
                'allowed_ips': sorted(allowed_ips),
                'locked': locked,
            }, f, ensure_ascii=False, indent=1)
    except Exception:
        pass


blocked_ips, allowed_ips, locked = _load_security()
user_ip = {}  # username -> client ip


def _check_secret():
    return bool(SERVER_SECRET) and request.args.get('secret', '') == SERVER_SECRET


def _kick_by_ip(ip, reason):
    """拉黑时把该 IP 的在线连接全部踢下线。"""
    with socket_lock:
        victims = [u for u, i in list(user_ip.items()) if i == ip]
        for u in victims:
            socks = user_sockets.get(u)
            if socks:
                for w in list(socks):
                    try:
                        safe_send(w, {'type': 'kicked', 'reason': reason})
                        w.close()
                    except Exception:
                        pass
                user_sockets.pop(u, None)
            online_users.discard(u)
            user_ip.pop(u, None)
    if victims:
        broadcast({'type': 'online_users', 'users': list(online_users)})


@app.route('/api/security/info', methods=['GET'])
def api_security_info():
    if not _check_secret():
        return jsonify({'ok': False, 'reason': 'invalid secret'}), 403
    online = []
    with socket_lock:
        for u, i in list(user_ip.items()):
            online.append({'user': u, 'ip': i})
    return jsonify({
        'ok': True,
        'online': online,
        'blocked_ips': sorted(blocked_ips),
        'allowed_ips': sorted(allowed_ips),
        'locked': locked,
    })


@app.route('/api/security/block', methods=['GET'])
def api_security_block():
    if not _check_secret():
        return jsonify({'ok': False, 'reason': 'invalid secret'}), 403
    ip = (request.args.get('ip', '') or '').strip()
    if not ip:
        return jsonify({'ok': False, 'reason': 'ip required'}), 400
    # 系统账号不可被拉黑（其在线 IP 同样受保护）
    sys_ip = user_ip.get(SYS_USER)
    if ip == sys_ip:
        return jsonify({'ok': False, 'reason': 'system account cannot be blocked'}), 400
    blocked_ips.add(ip)
    allowed_ips.discard(ip)
    _save_security()
    _kick_by_ip(ip, '您的 IP 已被拉黑')
    return jsonify({'ok': True, 'blocked_ips': sorted(blocked_ips)})


@app.route('/api/security/unblock', methods=['GET'])
def api_security_unblock():
    if not _check_secret():
        return jsonify({'ok': False, 'reason': 'invalid secret'}), 403
    ip = (request.args.get('ip', '') or '').strip()
    if not ip:
        return jsonify({'ok': False, 'reason': 'ip required'}), 400
    blocked_ips.discard(ip)
    _save_security()
    return jsonify({'ok': True, 'blocked_ips': sorted(blocked_ips)})


@app.route('/api/security/lock', methods=['GET'])
def api_security_lock():
    if not _check_secret():
        return jsonify({'ok': False, 'reason': 'invalid secret'}), 403
    global locked
    locked = request.args.get('on', '') == '1'
    _save_security()
    return jsonify({'ok': True, 'locked': locked, 'allowed_ips': sorted(allowed_ips)})


@app.route('/api/security/allow', methods=['GET'])
def api_security_allow():
    if not _check_secret():
        return jsonify({'ok': False, 'reason': 'invalid secret'}), 403
    ip = (request.args.get('ip', '') or '').strip()
    if not ip:
        return jsonify({'ok': False, 'reason': 'ip required'}), 400
    blocked_ips.discard(ip)
    allowed_ips.add(ip)
    _save_security()
    return jsonify({'ok': True, 'allowed_ips': sorted(allowed_ips)})


@app.route('/api/security/unallow', methods=['GET'])
def api_security_unallow():
    if not _check_secret():
        return jsonify({'ok': False, 'reason': 'invalid secret'}), 403
    ip = (request.args.get('ip', '') or '').strip()
    if not ip:
        return jsonify({'ok': False, 'reason': 'ip required'}), 400
    allowed_ips.discard(ip)
    _save_security()
    return jsonify({'ok': True, 'allowed_ips': sorted(allowed_ips)})


def find_free_port(preferred, host='0.0.0.0', max_tries=100):
    """Return the first free TCP port at or after `preferred` on `host`."""
    for p in range(preferred, preferred + max_tries):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind((host, p))
                return p
            except OSError:
                continue
    return None


if __name__ == '__main__':
    init_db()
    ensure_admin()
    ensure_system_user()
    # 支持 --port / --secret 参数（由启动器/父进程传入；源码与 exe 子进程模式共用）
    port = int(os.getenv('PORT', '3001'))
    for i, a in enumerate(sys.argv):
        if a == '--port' and i + 1 < len(sys.argv):
            try:
                port = int(sys.argv[i + 1])
            except ValueError:
                pass
        if a == '--secret' and i + 1 < len(sys.argv):
            SERVER_SECRET = sys.argv[i + 1] or None
    port = find_free_port(port) or port
    # 允许较大文件上传（与 Node 版一致）
    app.config['MAX_CONTENT_LENGTH'] = 500 * 1024 * 1024
    # 启用心跳保活，防止空闲连接被 NAT/代理静默断开
    start_heartbeat(interval=25)
    app.run(host='0.0.0.0', port=port, threaded=True, use_reloader=False)
