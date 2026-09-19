"""
假死回归测试（客户端部分）

背景：曾出现「点刷新后整个 IM 假死」——HTTP 还能应答、在线灯还是绿的，
但谁都发不出消息。根因是僵死客户端让服务端发送永久阻塞。
本脚本是这个问题唯一的回归防线，改动 server.py 的广播/发送逻辑后都应该跑一遍。

用法（服务端要单独起，本脚本只做客户端）：

    # 1) 起一份隔离的服务端（数据目录独立，不碰真实聊天库）
    mkdir -p /tmp/srv/python /tmp/srv/node/public
    cp python/server.py /tmp/srv/python/
    cd /tmp/srv/python && python server.py --port 3999 --secret testsecret

    # 2) 跑测试
    python python/tests/freeze_regression.py 3999

对照实验（证明每一处修复都必要）：
    python python/tests/make_controls.py     # 生成去掉某项修复的对照版服务端
    #   srvtest2 = 关掉 SO_SNDTIMEO 发送超时
    #   srvtest3 = 关掉 _drop_dead 失败摘除
    # 两个对照版跑同一脚本都应 FAIL；当前的 server.py 应 PASS。

已知的坑（写在这里免得下次重踩）：
- 僵尸连接必须用 sockopt 把 SO_RCVBUF 压到 1KB，否则内核接收缓冲会把灌进去的
  数据全吞掉，send() 根本不阻塞，对照组也会通过 —— 那是**假通过**。
- 环境里的代理会把 127.0.0.1 请求拦成 502，必须 ProxyHandler({}) 绕过。
- 用户已存在时改用 /api/login 取 token，脚本才能重复运行。
"""
import json
import sys
import time

import websocket

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 3999
BASE = f"http://127.0.0.1:{PORT}"
WS = f"ws://127.0.0.1:{PORT}/"
results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""), flush=True)


# 环境里有代理，会把 127.0.0.1 的请求也拦下（返回 502 Bad Gateway），这里显式绕过
import urllib.request
urllib.request.install_opener(
    urllib.request.build_opener(urllib.request.ProxyHandler({}))
)


def http_json(path, payload=None):
    import urllib.request
    url = BASE + path
    if payload is None:
        req = urllib.request.Request(url)
    else:
        req = urllib.request.Request(
            url, data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"}
        )
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read().decode())


def register_user(name):
    """注册取 token；用户已存在（脚本重复跑）则改用登录拿新 token。"""
    r = http_json("/api/register", {"username": name, "password": "pw"})
    if not r.get("ok"):
        r = http_json("/api/login", {"username": name, "password": "pw"})
    assert r.get("ok"), r
    return r["token"]


def ws_register(name, token, timeout=5, sockopt=None):
    ws = websocket.create_connection(WS, timeout=timeout, sockopt=sockopt)
    ws.send(json.dumps({"type": "register", "user": name, "token": token}))
    ws.settimeout(timeout)
    end = time.time() + timeout
    while time.time() < end:
        m = json.loads(ws.recv())
        if m.get("type") == "registered":
            return ws
    raise RuntimeError("register timeout")


def drain(ws, seconds=0.6):
    end = time.time() + seconds
    while time.time() < end:
        try:
            ws.settimeout(0.2)
            ws.recv()
        except Exception:
            return


def main():
    st = http_json("/api/status")
    check("HTTP /api/status", isinstance(st, dict))

    ta = register_user("alice")
    tb = register_user("bob")
    a = ws_register("alice", ta)
    b = ws_register("bob", tb)
    drain(a)
    drain(b)
    check("健康客户端注册", True)

    a.send(json.dumps({"type": "text", "user": "alice", "text": "baseline"}))
    t0 = time.time()
    got = None
    b.settimeout(5)
    end = time.time() + 5
    while time.time() < end:
        m = json.loads(b.recv())
        if m.get("text") == "baseline":
            got = m
            break
    check("基线：A 发 B 收", got is not None, f"{time.time()-t0:.2f}s")

    # 僵死客户端：注册后永不 read。
    # 关键：把它的接收缓冲压到 1KB——否则内核接收窗口会把灌进去的数据全吞掉，
    # 服务端 send() 压根不会阻塞，测试就成了空跑（对照组也会通过）。
    tc = register_user("zombie")
    z = ws_register("zombie", tc, sockopt=[(__import__("socket").SOL_SOCKET,
                                            __import__("socket").SO_RCVBUF, 1024)])

    # 灌足够多的数据，确保僵死连接的发送缓冲**一定**被塞满（否则 send 不会阻塞，
    # 测试就变成空跑）。每条约 600B × 2000 条 ≈ 1.2MB，远超默认发送缓冲。
    pad = "x" * 560
    for i in range(2000):
        a.send(json.dumps({"type": "text", "user": "alice", "text": f"flood-{i}-{pad}"}))
    time.sleep(2.0)

    a.send(json.dumps({"type": "text", "user": "alice", "text": "after-zombie"}))
    got2, t1 = None, time.time()
    b.settimeout(25)
    end = time.time() + 25
    while time.time() < end:
        try:
            m = json.loads(b.recv())
        except Exception:
            break
        if m.get("text") == "after-zombie":
            got2 = m
            break
    elapsed = time.time() - t1
    check("僵死连接不拖垮健康客户端", got2 is not None,
          f"{elapsed:.2f}s" + ("" if got2 else "  <- 假死复现"))

    try:
        t2 = time.time()
        http_json("/api/status")
        check("僵死存在时 HTTP 仍响应", True, f"{(time.time()-t2)*1000:.0f}ms")
    except Exception as e:
        check("僵死存在时 HTTP 仍响应", False, str(e))

    for c in (a, b, z):
        try:
            c.close()
        except Exception:
            pass

    print("\n" + "=" * 46)
    bad = [n for n, ok, _ in results if not ok]
    print(f"共 {len(results)} 项，通过 {len(results)-len(bad)} 项")
    if bad:
        print("失败：", ", ".join(bad))
        sys.exit(1)
    print("全部通过")


if __name__ == "__main__":
    main()
