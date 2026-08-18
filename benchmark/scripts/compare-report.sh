#!/bin/bash
# =============================================
# 聚合对比报告：解析本轮全部 result-*.json，输出终端对比表 + markdown
# 支持多轮聚合：同 (version, mode, rate) 的 N 轮（result-*-round{N}-*.json）取中位数 + min/max 区间
# 主指标 = 端到端成功率（落库率，order_diff.end_to_end_success_rate）；受理成功率/延迟为次级
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
import statistics
import sys

result_dir, out_md = sys.argv[1], sys.argv[2]
files = sorted(f for f in os.listdir(result_dir) if f.startswith("result-") and f.endswith(".json"))


def median(vals):
    return round(statistics.median(vals), 4) if vals else None


def fmt_pct(v):
    return f"{v * 100:.1f}%" if v is not None else "N/A"


def fmt_range(vals, pct=False):
    """中位数 (min-max)；单轮无区间"""
    if not vals:
        return "N/A"
    if len(vals) == 1:
        return fmt_pct(vals[0]) if pct else str(round(vals[0], 1))
    m = statistics.median(vals)
    lo, hi = min(vals), max(vals)
    if pct:
        return f"{m * 100:.1f}% ({lo * 100:.1f}-{hi * 100:.1f}%)"
    return f"{round(m, 1)} ({round(lo, 1)}-{round(hi, 1)})"


def load(path):
    with open(path) as f:
        return json.load(f)


# 收集所有轮次
rounds = []  # (version, mode, rate, round_index, result)
for f in files:
    d = load(os.path.join(result_dir, f))
    g = d.get("gatling", {})
    fl = d.get("failures", {})
    od = d.get("order_diff", {})
    biz = fl.get("business", {})
    protocol = fl.get("protocol_no_json", 0)
    rounds.append({
        "version": d.get("version"),
        "mode": d.get("mode", "closed"),
        "rate": (d.get("rate") or d.get("concurrency")) if d.get("mode") == "openloop" else d.get("concurrency"),
        "e2e": od.get("end_to_end_success_rate"),
        "accept": g.get("success_rate"),
        "total": g.get("total"),
        "p50": g.get("p50_ms"),
        "p95": g.get("p95_ms"),
        "p99": g.get("p99_ms"),
        "conn": protocol,
        "f70005": biz.get("70005", 0),
        "fseat": biz.get("40002", 0) + biz.get("40003", 0),
        "f50009": biz.get("50009", 0),
        "fother": sum(v for k, v in biz.items() if k not in ("-100", "70005", "40002", "40003", "0", "50009")),
        "f100": biz.get("-100", 0),
        "delta": od.get("delta"),
        "snapshot": d.get("snapshot", {}),
    })

# 按 (version, mode, rate) 分组（多轮聚合）
groups = {}
for r in rounds:
    key = (r["version"], r["mode"], r["rate"])
    groups.setdefault(key, []).append(r)

order = sorted(groups.keys(), key=lambda k: (k[0], k[1], k[2]))

# 失败桶只对存在该桶的组展示中位/求和（多轮求和，单轮原值）
def sum_field(rs, key):
    return sum(r[key] for r in rs) if len(rs) > 1 else (rs[0][key] if rs else 0)


header = ("| version | mode | rate | 端到端成功率(落库率) | 受理成功率 | p50(ms) | p95(ms) | p99(ms) | "
          "协议/连接 | 70005 | 40002/3 | 50009 | 其他业务 | 落库差 |")
sep = ("|---------|------|------|---------------------|------------|---------|---------|---------|"
       "-----------|-------|---------|-------|----------|--------|")
lines = [header, sep]
for key in order:
    v, m, r = key
    rs = groups[key]
    e2e = [x["e2e"] for x in rs if x["e2e"] is not None]
    acc = [x["accept"] for x in rs if x["accept"] is not None]
    p50 = [x["p50"] for x in rs if x["p50"] is not None]
    p95 = [x["p95"] for x in rs if x["p95"] is not None]
    p99 = [x["p99"] for x in rs if x["p99"] is not None]
    lines.append(
        f"| {v} | {m} | {r} | {fmt_range(e2e, pct=True)} | {fmt_range(acc, pct=True)} | "
        f"{fmt_range(p50)} | {fmt_range(p95)} | {fmt_range(p99)} | "
        f"{sum_field(rs, 'conn')} | {sum_field(rs, 'f70005')} | {sum_field(rs, 'fseat')} | "
        f"{sum_field(rs, 'f50009')} | {sum_field(rs, 'fother')} | {sum_field(rs, 'delta')} |"
    )

text = "\n".join(lines)

# 拐点标注：每个版本按 rate 升序，找端到端成功率首次跌破 99% / 95% 的 rate
def rate_val(r):
    try:
        return int(str(r))
    except (TypeError, ValueError):
        return 0


knee_lines = []
for v in sorted({k[0] for k in order}):
    by_mode = {}
    for key in order:
        if key[0] != v:
            continue
        by_mode.setdefault(key[1], []).append(key)
    for mode, keys in sorted(by_mode.items()):
        keys_sorted = sorted(keys, key=lambda k: rate_val(k[2]))
        knee99 = knee95 = None
        for key in keys_sorted:
            e2e_vals = [x["e2e"] for x in groups[key] if x["e2e"] is not None]
            if not e2e_vals:
                continue
            m = statistics.median(e2e_vals)
            if knee99 is None and m < 0.99:
                knee99 = rate_val(key[2])
            if knee95 is None and m < 0.95:
                knee95 = rate_val(key[2])
        label99 = str(knee99) if knee99 is not None else "未跌破(测到最高档)"
        label95 = str(knee95) if knee95 is not None else "未跌破(测到最高档)"
        knee_lines.append(f"- **{v}** ({mode})：端到端成功率首次跌破 99% @ **{label99} QPS**；跌破 95% @ **{label95} QPS**")

knee_text = "\n".join(knee_lines) if knee_lines else "- （无拐点数据）"

# 快照摘要（取第一轮的 git commit 做展示）
snap_commits = {}
for key in order:
    v = key[0]
    snap = groups[key][0].get("snapshot", {}) or {}
    if snap.get("git_commit") and snap.get("git_commit") != "unknown":
        snap_commits[v] = snap["git_commit"]
snap_text = "、".join(f"{v}={c}" for v, c in snap_commits.items()) or "N/A（无快照）"

with open(out_md, "w") as f:
    f.write(f"# 对比报告 {out_md.split('compare-report-')[1][:14]}\n\n")
    f.write("> 端到端成功率 = 落库订单数 ÷ 请求数（消费排空 lag=0 后统计，异步架构主指标）\n")
    f.write("> 受理成功率 = HTTP 200 且业务 code=0 的请求占比（Gatling ok/total，次级指标）\n")
    f.write("> 多轮时输出中位数 (min-max)；落库差 = 压测前后 d_order 增量\n")
    f.write("> 快照：被测配置见 results/snapshot-*.json\n\n")
    f.write("## 被测配置（git commit）\n\n")
    f.write(snap_text + "\n\n")
    f.write("## 对比表\n\n")
    f.write(text + "\n\n")
    f.write("## 容量拐点（端到端成功率跌破阈值）\n\n")
    f.write(knee_text + "\n\n")
    f.write("## 公平性声明\n\n")
    f.write("- 压测机 = 被测机（同机压测）：高并发下压测机可能成为瓶颈，协议/连接失败需结合压测机 CPU 判断；跨机压测另行执行\n")
    f.write("- 每档时长默认 60s；多轮结果取中位数与区间，不取最优轮\n")
    f.write("- 失败分桶：协议/连接=系统过载、70005=锁竞争、40002/3=座位竞争、50009=限购、落库差=异步正确性丢失\n")

print(text)
print("\n## 容量拐点（端到端成功率跌破阈值）")
print(knee_text)
print(f"\n报告已保存: {out_md}")
PYEOF
