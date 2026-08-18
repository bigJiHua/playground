// Service Worker - 处理系统通知的按钮点击
let activeClients = new Set();

self.addEventListener('message', (event) => {
  if (event.data.type === 'ping') {
    event.source.postMessage({ type: 'pong' });
  } else if (event.data.type === 'clientReady') {
    activeClients.add(event.source.id);
    event.waitUntil(
      (async () => {
        const allClients = await self.clients.matchAll({ type: 'window' });
        const activeIds = new Set(allClients.map(c => c.id));
        for (let id of activeClients) {
          if (!activeIds.has(id)) activeClients.delete(id);
        }
      })()
    );
  }
});

async function getTargetClient() {
  for (let id of activeClients) {
    const client = await self.clients.get(id);
    if (client) return client;
  }
  const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  return clients[0];
}

self.addEventListener('notificationclick', (event) => {
  const notification = event.notification;
  const action = event.action;
  const data = notification.data || {};
  notification.close();

  if (action === 'copy' && data.text) {
    event.waitUntil(
      (async () => {
        const target = await getTargetClient();
        if (target) {
          target.postMessage({ type: 'copy-text', text: data.text });
          // 聚焦窗口让 clipboard API 能正常工作
          await target.focus();
        }
      })()
    );
  } else if (action === 'goto' && data.msgId) {
    event.waitUntil(
      (async () => {
        const target = await getTargetClient();
        if (target) {
          target.postMessage({ type: 'goto-msg', msgId: data.msgId });
          await target.focus();
        }
      })()
    );
  } else if (!action) {
    event.waitUntil(
      (async () => {
        const target = await getTargetClient();
        if (target) await target.focus();
      })()
    );
  }
});
