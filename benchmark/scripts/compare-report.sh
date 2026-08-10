#!/bin/bash
# =============================================
# 聚合对比报告：解析本轮全部 result-*.json，输出终端对比表 + markdown
# 用法: bash scripts/compare-report.sh
# 输入: results/result-*.json（run-single.sh 生成）
# 输出: results/compare-report-<时间戳>.md
# =============================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULT_DIR="$PROJECT_DIR/results"

TIMESTAMP=$(date +%Y%m%d%H%M%S)
OUT_MD="$RESULT_DIR/compare-report-${TIMESTAMP}.md"

RESULT_FILES=$(ls "$RESULT_DIR"/result-*.json 2>/dev/null || true)
if [ -z "$RESULT_FILES" ]; then
  echo "ERROR: results/ 下没有 result-*.json（先跑 run-single.sh）" >&2
  exit 1
fi

python3 - "$RESULT_DIR" "$OUT_MD" <<'PYEOF'
import json
import os
import sys

result_dir, out_md = sys.argv[1], sys.argv[2]
files = sorted(f for f in os.listdir(result_dir) if f.startswith("result-") and f.endswith(".json"))

rows = []
for f in files:
    with open(os.path.join(result_dir, f)) as fp:
        d = json.load(fp)
    g = d.get("gatling", {})
    fl = d.get("failures", {})
    od = d.get("order_diff", {})
    biz = fl.get("business", {})
    protocol = fl.get("protocol_no_json", 0)
    rows.append({
        "version": d.get("version"),
        "mode": d.get("mode", "closed"),
        "rate": d.get("rate") or d.get("concurrency"),
        "success_rate": g.get("success_rate"),
        "total": g.get("total"),
        "p50": g.get("p50_ms"),
        "p95": g.get("p95_ms"),
        "failures": {
            "-100": biz.get("-100", 0),
            "70005": biz.get("70005", 0),
            "40002": biz.get("40002", 0),
            "40003": biz.get("40003", 0),
            "other_biz": sum(v for k, v in biz.items() if k not in ("-100", "70005", "40002", "40003", "0")),
            "protocol_no_json": protocol,
        },
        "order_delta": od.get("delta"),
        "success_count": od.get("success_count"),
    })

rows.sort(key=lambda r: (r["version"], r["rate"]))

header = "| version | mode | rate/并发 | 成功率 | 总请求 | p50(ms) | p95(ms) | -100 | 70005 | 40002 | 40003 | 其他业务 | 协议/连接 | 落库差 |"
sep = "|---------|------|----------|--------|--------|---------|---------|------|-------|-------|-------|----------|-----------|--------|"

lines = [header, sep]
for r in rows:
    f = r["failures"]
    rate = f"{r['success_rate']*100:.1f}%" if r["success_rate"] is not None else "N/A"
    lines.append(
        f"| {r['version']} | {r['mode']} | {r['rate']} | {rate} | {r['total']} | "
        f"{r['p50']} | {r['p95']} | {f['-100']} | {f['70005']} | {f['40002']} | {f['40003']} | "
        f"{f['other_biz']} | {f['protocol_no_json']} | {r['order_delta']} |"
    )

text = "\n".join(lines)
print(text)

with open(out_md, "w") as f:
    f.write(f"# 对比报告 {out_md.split('compare-report-')[1][:14]}\n\n")
    f.write("> 成功率 = HTTP 200 且业务 code=0 的请求占比（Gatling ok/total）\n")
    f.write("> 落库差 = 压测前后 d_order 增量（应 ≈ 成功数；v4 差异反映 mq 消费链路）\n\n")
    f.write(text + "\n")
print(f"\n报告已保存: {out_md}")
PYEOF
