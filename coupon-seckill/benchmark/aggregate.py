#!/usr/bin/env python3
"""聚合 benchmark-results/result-*.json，输出汇总表 + 容量拐点标注，生成 markdown 片段。"""
import json, glob, os, sys

files = sorted(glob.glob("benchmark-results/result-*.json"))
if not files:
    print("no result files found")
    sys.exit(1)

rows = []
for f in files:
    if "smoke" in f:
        continue
    with open(f) as fp:
        rows.append(json.load(fp))

rows.sort(key=lambda r: r["rate"])

print(f"{'rate':>6} {'reqs':>7} {'受理率%':>8} {'落库率%':>8} {'p50ms':>7} {'p95ms':>7} {'p99ms':>7} {'maxms':>7} {'httpErr':>7} {'业务拒绝':>8}")
for r in rows:
    buckets = r.get("errorBuckets", {})
    biz_reject = sum(buckets.values())
    print(f"{r['rate']:>6} {r['requests']:>7} {r['acceptRate']:>8.2f} {r['e2eSuccessRate']:>8.2f} "
          f"{r['p50Ms']:>7.2f} {r['p95Ms']:>7.2f} {r['p99Ms']:>7.2f} {r['maxMs']:>7.2f} "
          f"{r['httpErrors']:>7} {biz_reject:>8}")

# 容量拐点：落库率跌破 99% / 95%
def first_below(threshold):
    for r in rows:
        if r["e2eSuccessRate"] < threshold:
            return r["rate"]
    return None

knee99 = first_below(99)
knee95 = first_below(95)
print("\n[容量拐点] 端到端落库率首次跌破 99%: rate=" + str(knee99) + ", 跌破 95%: rate=" + str(knee95))

peak = max(rows, key=lambda r: r["e2eSuccessRate"] * r["rate"])
print(f"[峰值吞吐] 保持 100% 落库的最高档: rate={peak['rate']}/s ({peak['e2eSuccessRate']}%)")

with open("benchmark-results/aggregate.json", "w") as fp:
    json.dump(rows, fp, ensure_ascii=False, indent=2)
print("\naggregate written to benchmark-results/aggregate.json")
