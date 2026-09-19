"""
APK 上传→下载 往返完整性测试

为什么需要它：安卓端报「安装包位置错误 / 未知错误」时，排查很容易只盯着客户端。
但如果服务端在上传或下载环节把字节改坏了（比如走了文本模式、被 base64 绕了一道），
客户端再怎么修都装不上。这个测试用 SHA256 一次性把服务端这条链路钉死。

用法：
    # 起一份隔离服务端（数据目录独立，不碰真实 uploads）
    mkdir -p /tmp/srv/python /tmp/srv/node/public
    cp python/server.py /tmp/srv/python/
    cd /tmp/srv/python && python server.py --port 3999 --secret testsecret

    python python/tests/apk_roundtrip_test.py <apk路径> 3999

判定：上传后分别经 /api/download 与 /uploads/<f> 下载，
      两次的 SHA256 都必须与原文件一致，否则服务端传输有 bug。
"""
import hashlib
import json
import os
import sys
import urllib.request

# 环境里有代理会把 127.0.0.1 拦成 502，必须绕过
urllib.request.install_opener(
    urllib.request.build_opener(urllib.request.ProxyHandler({}))
)

OK, FAIL = "PASS", "FAIL"
results = []


def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"[{OK if ok else FAIL}] {name}" + (f"  {detail}" if detail else ""), flush=True)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def post_file(base: str, path: str) -> dict:
    boundary = "----apk_roundtrip_boundary"
    with open(path, "rb") as f:
        content = f.read()
    filename = os.path.basename(path)
    body = b""
    body += f"--{boundary}\r\n".encode()
    body += f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode()
    body += b"Content-Type: application/octet-stream\r\n\r\n"
    body += content
    body += f"\r\n--{boundary}--\r\n".encode()

    req = urllib.request.Request(
        base + "/upload", data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode())


def get_bytes(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else ""
    port = sys.argv[2] if len(sys.argv) > 2 else "3999"
    if not apk or not os.path.isfile(apk):
        print("用法: python apk_roundtrip_test.py <apk路径> [端口]")
        sys.exit(2)

    base = f"http://127.0.0.1:{port}"
    with open(apk, "rb") as f:
        original = f.read()
    origin_sha = sha256(original)
    print(f"原文件: {apk}")
    print(f"  大小 {len(original)} 字节  sha256={origin_sha[:16]}...")
    print(f"  文件头 {original[:2]!r} {'(zip/OK)' if original[:2] == b'PK' else '(异常)'}")
    print()

    up = post_file(base, apk)
    check("上传成功", bool(up.get("url")), str(up))
    if not up.get("url"):
        sys.exit(1)

    stored = os.path.basename(up["url"])
    check("上传后服务端记录的大小一致",
          up.get("size") == len(original),
          f"服务端 {up.get('size')} / 本地 {len(original)}")

    # 路径一：/api/download（安卓端下载按钮走这条）
    dl = get_bytes(f"{base}/api/download?f={stored}&name={os.path.basename(apk)}")
    check("api/download 字节一致", sha256(dl) == origin_sha,
          f"{len(dl)} 字节 sha256={sha256(dl)[:16]}...")

    # 路径二：/uploads/<f>（浏览器/其它客户端走这条）
    sv = get_bytes(f"{base}/uploads/{stored}")
    check("uploads/<f> 字节一致", sha256(sv) == origin_sha,
          f"{len(sv)} 字节 sha256={sha256(sv)[:16]}...")

    print("\n" + "=" * 46)
    bad = [n for n, ok in results if not ok]
    print(f"共 {len(results)} 项，通过 {len(results) - len(bad)} 项")
    if bad:
        print("失败：", ", ".join(bad))
        print("→ 服务端传输环节损坏了文件，客户端改什么都没用")
        sys.exit(1)
    print("服务端传输无损，安装问题请查客户端 / 签名")


if __name__ == "__main__":
    main()
