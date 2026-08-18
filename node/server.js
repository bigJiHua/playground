const express = require('express');
const WebSocket = require('ws');
const path = require('path');
const crypto = require('crypto');
const multer = require('multer');
const Database = require('better-sqlite3');
const fs = require('fs');

// 常用扩展名 -> MIME，用于下载时设置正确的 Content-Type
const MIME_TYPES = {
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png',
  '.gif': 'image/gif', '.webp': 'image/webp', '.bmp': 'image/bmp',
  '.svg': 'image/svg+xml', '.mp4': 'video/mp4', '.webm': 'video/webm',
  '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.ogg': 'audio/ogg',
  '.pdf': 'application/pdf', '.txt': 'text/plain; charset=utf-8',
  '.json': 'application/json', '.zip': 'application/zip',
  '.doc': 'application/msword', '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.xls': 'application/vnd.ms-excel', '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
};

const app = express();
const server = require('http').createServer(app);
const wss = new WebSocket.Server({ server });

// 数据与上传目录固定在项目根（node/ 的上一级），Node 与 Python 桌面版共用同一份数据
const DATA_DIR = path.join(__dirname, '..', 'data');
const UPLOADS_DIR = path.join(__dirname, '..', 'uploads');

if (!require('fs').existsSync(DATA_DIR)) require('fs').mkdirSync(DATA_DIR);
if (!require('fs').existsSync(UPLOADS_DIR)) require('fs').mkdirSync(UPLOADS_DIR);

const db = new Database(path.join(DATA_DIR, 'chat.db'));
db.pragma('journal_mode = WAL');

db.exec(`CREATE TABLE IF NOT EXISTS users (
  username TEXT PRIMARY KEY,
  password_hash TEXT NOT NULL,
  token TEXT NOT NULL
)`);

db.exec(`CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user TEXT NOT NULL,
  text TEXT,
  type TEXT NOT NULL DEFAULT 'text',
  image_url TEXT,
  file_name TEXT,
  file_size INTEGER,
  reply_to INTEGER,
  created_at TEXT NOT NULL
)`);

try { db.exec('ALTER TABLE messages ADD COLUMN file_name TEXT'); } catch {}
try { db.exec('ALTER TABLE messages ADD COLUMN file_size INTEGER'); } catch {}

const insertMsg = db.prepare('INSERT INTO messages (user, text, type, image_url, file_name, file_size, reply_to, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)');
const getMsgById = db.prepare('SELECT * FROM messages WHERE id = ?');
const getMsgsByDate = db.prepare("SELECT * FROM messages WHERE date(created_at) = ? ORDER BY id");
const getDates = db.prepare("SELECT DISTINCT date(created_at) as d FROM messages ORDER BY d DESC");
const searchMsgs = db.prepare("SELECT * FROM messages WHERE text LIKE ? ORDER BY id");

function localTimestamp() {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}T${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}:${String(now.getSeconds()).padStart(2,'0')}`;
}

function broadcastPayload(payload) {
    const data = JSON.stringify(payload);
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            try { client.send(data); } catch (e) { /* 忽略已半关闭的连接 */ }
        }
    });
}

// 安全的单点发送：捕获半关闭连接抛出的异常
function safeSend(ws, data) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        try { ws.send(data); return true; } catch (e) { return false; }
    }
    return false;
}

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));
app.use('/uploads', express.static(UPLOADS_DIR));

const upload = multer({ dest: UPLOADS_DIR, limits: { fileSize: Infinity } });

app.post('/upload', upload.single('file'), (req, res) => {
    const fileUrl = `/uploads/${req.file.filename}`;
    const originalName = req.file.originalname;
    res.json({ url: fileUrl, name: originalName, size: req.file.size });
});

// 下载接口：通过附件方式返回文件，保证浏览器下载而非预览/导航，并保留原始文件名与扩展名
app.get('/api/download', (req, res) => {
    const f = path.basename(String(req.query.f || ''));
    const name = String(req.query.name || f || 'download');
    if (!f) return res.status(400).json({ error: 'invalid file' });

    const filePath = path.join(UPLOADS_DIR, f);
    const safeRoot = path.resolve(UPLOADS_DIR);
    // 严格防止路径穿越
    if (path.resolve(filePath) !== filePath || !filePath.startsWith(safeRoot + path.sep)) {
        return res.status(400).json({ error: 'invalid path' });
    }
    if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
        return res.status(404).json({ error: 'file not found' });
    }

    const ext = path.extname(name).toLowerCase();
    const mime = MIME_TYPES[ext] || 'application/octet-stream';
    const asciiName = name.replace(/[^\x20-\x7E]/g, '_'); // ASCII 兜底名
    res.setHeader('Content-Type', mime);
    res.setHeader('Content-Disposition',
        `attachment; filename="${asciiName}"; filename*=UTF-8''${encodeURIComponent(name)}`);
    res.setHeader('Cache-Control', 'no-store');
    fs.createReadStream(filePath).pipe(res);
});

function hashPassword(password) {
    return crypto.createHash('sha256').update(password).digest('hex');
}

function generateToken() {
    return crypto.randomBytes(16).toString('hex');
}

app.post('/api/register', (req, res) => {
    const { username, password } = req.body;
    if (!username || !password) return res.json({ ok: false, reason: '用户名和密码不能为空' });
    const exists = db.prepare('SELECT username FROM users WHERE username = ?').get(username);
    if (exists) return res.json({ ok: false, reason: '用户名已存在' });
    const token = generateToken();
    db.prepare('INSERT INTO users (username, password_hash, token) VALUES (?, ?, ?)').run(username, hashPassword(password), token);
    res.json({ ok: true, token, username });
});

app.post('/api/login', (req, res) => {
    const { username, password } = req.body;
    if (!username || !password) return res.json({ ok: false, reason: '用户名和密码不能为空' });
    const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username);
    if (!user) return res.json({ ok: false, reason: '用户不存在' });
    if (user.password_hash !== hashPassword(password)) return res.json({ ok: false, reason: '密码错误' });
    const token = generateToken();
    db.prepare('UPDATE users SET token = ? WHERE username = ?').run(token, username);
    res.json({ ok: true, token, username });
});

app.get('/api/history/dates', (req, res) => {
    const rows = getDates.all();
    res.json({ dates: rows.map(r => r.d) });
});

app.get('/api/history/messages', (req, res) => {
    const date = req.query.date;
    if (!date) return res.json({ messages: [] });
    const messages = getMsgsByDate.all(date);
    res.json({ messages });
});

app.get('/api/search', (req, res) => {
    const q = req.query.q;
    if (!q) return res.json({ messages: [] });
    const messages = searchMsgs.all(`%${q}%`);
    res.json({ messages });
});

const onlineUsers = new Set();
const userConnections = new Map();

function broadcastOnlineUsers() {
    const list = Array.from(onlineUsers);
    broadcastPayload({ type: 'online_users', users: list });
}

wss.on('connection', (ws) => {
    let registeredUser = null;
    ws.isAlive = true;
    // 浏览器会自动对协议层 ping 回 pong，这里仅用于标记存活
    ws.on('pong', () => { ws.isAlive = true; });
    // 捕获连接级错误，避免未处理异常导致进程退出
    ws.on('error', () => {});

    ws.on('message', (data) => {
        const msg = JSON.parse(data);

        // 应用层心跳：客户端发 ping，服务端回 pong，保持连接活跃
        if (msg.type === 'ping') {
            safeSend(ws, JSON.stringify({ type: 'pong' }));
            return;
        }

        if (msg.type === 'register') {
            const user = db.prepare('SELECT * FROM users WHERE username = ?').get(msg.user);
            if (user && user.token === msg.token) {
                // 支持同一账号多设备同时在线（刷新/多端不再互踢）
                if (!userConnections.has(registeredUser)) userConnections.set(registeredUser, new Set());
                userConnections.get(registeredUser).add(ws);

                registeredUser = msg.user;
                const wasOnline = onlineUsers.has(registeredUser);
                onlineUsers.add(registeredUser);
                ws.send(JSON.stringify({ type: 'registered', ok: true }));
                const today = new Date();
                const dateStr = `${today.getFullYear()}-${String(today.getMonth()+1).padStart(2,'0')}-${String(today.getDate()).padStart(2,'0')}`;
                ws.send(JSON.stringify({ type: 'history', messages: getMsgsByDate.all(dateStr) }));
                broadcastOnlineUsers();
                if (!wasOnline) {
                    const text = `${registeredUser} 加入了群聊`;
                    const info = insertMsg.run(registeredUser, text, 'system_join', null, null, null, null, localTimestamp());
                    const saved = getMsgById.get(info.lastInsertRowid);
                    broadcastPayload(saved);
                }
            } else {
                ws.send(JSON.stringify({ type: 'registered', ok: false, reason: '身份验证失败' }));
                ws.close();
            }
            return;
        }

        if (!registeredUser) {
            ws.send(JSON.stringify({ type: 'error', reason: '请先登录' }));
            return;
        }

        const created_at = localTimestamp();

        const info = insertMsg.run(registeredUser, msg.text || null, msg.type, msg.image_url || null, msg.file_name || null, msg.file_size || null, msg.reply_to || null, created_at);
        const saved = db.prepare('SELECT * FROM messages WHERE id = ?').get(info.lastInsertRowid);

        if (saved.reply_to) {
            const replied = getMsgById.get(saved.reply_to);
            if (replied) {
                saved.reply_to_user = replied.user;
                saved.reply_to_text = replied.text || (replied.type === 'image' ? '[图片]' : '[文件]');
            }
        }

        if (msg._clientId) saved._clientId = msg._clientId;

        wss.clients.forEach(client => {
            safeSend(client, JSON.stringify(saved));
        });
    });

    ws.on('close', () => {
        if (registeredUser) {
            const socks = userConnections.get(registeredUser);
            if (socks) {
                socks.delete(ws);
                if (socks.size === 0) userConnections.delete(registeredUser);
            }
            // 仅当该账号已无任何连接时才算离线
            if (!userConnections.has(registeredUser)) {
                onlineUsers.delete(registeredUser);
                broadcastOnlineUsers();
            }
        }
    });
});

// 服务端心跳：定期 ping 所有连接。浏览器自动回 pong；
// 未在周期内回应的连接视为已死，直接 terminate，防止僵死连接堆积并触发客户端重连。
const HEARTBEAT_INTERVAL = 25000;
const heartbeat = setInterval(() => {
    wss.clients.forEach((ws) => {
        if (ws.isAlive === false) {
            try { ws.terminate(); } catch (e) {}
            return;
        }
        ws.isAlive = false;
        try { ws.ping(); } catch (e) {}
    });
}, HEARTBEAT_INTERVAL);

// 进程退出时清理定时器，避免句柄泄漏
server.on('close', () => clearInterval(heartbeat));

const PORT = process.env.PORT || 3001;
server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
        console.error(`\n[启动失败] 端口 ${PORT} 已被占用。`);
        console.error(`请先结束占用该端口的进程，常见命令（Windows PowerShell）：`);
        console.error(`  Get-NetTCPConnection -LocalPort ${PORT} | Select-Object OwningProcess`);
        console.error(`  # 记录上面的 PID，然后：`);
        console.error(`  Stop-Process -Id <PID> -Force`);
        console.error(`或者用 cmd： netstat -ano | findstr :${PORT}  然后  taskkill /PID <PID> /F`);
        console.error(`若想临时换端口启动： PORT=3100 node server.js\n`);
        process.exit(1);
    } else {
        console.error('[启动失败] 服务器异常：', err);
        process.exit(1);
    }
});
server.listen(PORT, '0.0.0.0', () => console.log(`服务器运行在 http://0.0.0.0:${PORT}`));
