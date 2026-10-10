#!/usr/bin/env python3
# =============================================================================
# 本地告警接收器（Alertmanager webhook sink）
#
# 用途：验证「Prometheus 规则 -> Alertmanager -> 接收端」整条告警闭环。
#       Alertmanager 把告警 POST 到 http://host.docker.internal:8089/alerts，
#       本脚本按收到顺序打印告警名 / 级别 / 摘要。
#
# 用法：
#   python3 scripts/alert-sink.py            # 监听 :8089
#   python3 scripts/alert-sink.py 9099       # 自定义端口
#   make sink                                # 等价于上面的默认端口
#
# 依赖：仅标准库，无需 pip 安装。
# =============================================================================
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

# 重定向到文件时默认块缓冲，这里改为行缓冲，便于实时观察告警投递
try:
    sys.stdout.reconfigure(line_buffering=True)
except AttributeError:
    pass

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8089


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            data = json.loads(body)
        except Exception:
            data = {"status": "unparsed", "alerts": [], "raw": body.decode("utf-8", "replace")}

        alerts = data.get("alerts", [])
        print(f"\n[{data.get('status', '?')}] received {len(alerts)} alert(s)")
        for alert in alerts:
            labels = alert.get("labels", {})
            summary = alert.get("annotations", {}).get("summary", "")
            print(f"  - {labels.get('alertname')} [{labels.get('severity')}] {summary}")
        if "raw" in data:
            print(f"  raw: {data['raw']}")

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"alert sink listening on :{PORT} (POST /alerts)")
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
