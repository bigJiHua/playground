const express = require('express');
const WebSocket = require('ws');
const path = require('path');
const crypto = require('crypto');
const multer = require('multer');
const Database = require('better-sqlite3');

const app = express();
const server = require('http').createServer(app);
const wss = new WebSocket.Server({ server });

const DATA_DIR = path.join(__dirname, 'data');
const UPLOADS_DIR = path.join(__dirname, 'uploads');

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
        if (client.readyState === WebSocket.OPEN) client.send(data);
    });
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

    ws.on('message', (data) => {
        const msg = JSON.parse(data);

        if (msg.type === 'register') {
            const user = db.prepare('SELECT * FROM users WHERE username = ?').get(msg.user);
            if (user && user.token === msg.token) {
                const existing = userConnections.get(msg.user);
                if (existing && existing !== ws && existing.readyState === WebSocket.OPEN) {
                    existing.send(JSON.stringify({ type: 'kicked', reason: '您的账号已在其他设备登录' }));
                    existing.close();
                }

                registeredUser = msg.user;
                const wasOnline = onlineUsers.has(registeredUser);
                userConnections.set(registeredUser, ws);
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
            if (client.readyState === WebSocket.OPEN) {
                client.send(JSON.stringify(saved));
            }
        });
    });

    ws.on('close', () => {
        if (registeredUser) {
            if (userConnections.get(registeredUser) === ws) {
                userConnections.delete(registeredUser);
                onlineUsers.delete(registeredUser);
                broadcastOnlineUsers();
            }
        }
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => console.log(`服务器运行在 http://0.0.0.0:${PORT}`));
