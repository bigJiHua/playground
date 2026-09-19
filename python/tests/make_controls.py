"""生成两个对照组服务端，用来证明每一处修复都是必要的。

对照A：保留「失败摘除」，关掉「发送超时」→ 验证 SO_SNDTIMEO 是否必要
对照B：保留「发送超时」，关掉「失败摘除」→ 验证 _drop_dead 是否必要
"""
import os

SRC = r"D:\Code\play\im\python\server.py"
TIMEOUT_ANCHOR = "sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDTIMEO, int(send_timeout * 1000))"
DROP_ANCHOR = "    if drop_dead:\n        _drop_dead(dead)"

src = open(SRC, encoding="utf-8").read()
assert TIMEOUT_ANCHOR in src, "timeout anchor not found"
assert DROP_ANCHOR in src, "drop anchor not found"

for name, text in (("srvtest2", src.replace(TIMEOUT_ANCHOR, "pass  # [对照A] 关闭发送超时")),
                   ("srvtest3", src.replace(DROP_ANCHOR, "    pass  # [对照B] 关闭失败摘除"))):
    d = os.path.join(r"D:\Code\play\im\temp", name, "python")
    os.makedirs(d, exist_ok=True)
    os.makedirs(os.path.join(r"D:\Code\play\im\temp", name, "node", "public"), exist_ok=True)
    open(os.path.join(d, "server.py"), "w", encoding="utf-8").write(text)
    print("生成", name)
