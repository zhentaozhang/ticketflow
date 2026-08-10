#!/usr/bin/env python3
"""汇总单轮压测结果：Gatling stats + 业务失败分类 + 落库差额 → 每轮 result json。

失败分类规则（spec §3）：
- 业务码（-100/70005/40002/40003 等）来自 failure-*.json（HTTP 200 响应体的 respCode）
- NO_JSON 桶 = 无响应体的请求（超时/连接错误/罕见非 200），归入 protocol_no_json
- 对账：protocol_no_json + 业务失败数 ≈ simulation.log 的 KO 总数（差异记 gap）
"""
import argparse
import json
import os
import sys


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--label", required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--concurrency", type=int, required=True)
    p.add_argument("--duration", type=int, required=True)
    p.add_argument("--stats", required=True)
    p.add_argument("--failure", required=True)
    p.add_argument("--sim-log", required=True)
    p.add_argument("--orders-before", type=int, required=True)
    p.add_argument("--orders-after", type=int, required=True)
    p.add_argument("--metrics", required=True)
    p.add_argument("--out", required=True)
    return p.parse_args()


def load_json(path):
    with open(path) as f:
        return json.load(f)


def main():
    args = parse_args()
    if not os.path.exists(args.stats):
        sys.exit(f"ERROR: stats.json 不存在: {args.stats}")
    if not os.path.exists(args.failure):
        sys.exit(f"ERROR: failure json 不存在: {args.failure}")

    stats = load_json(args.stats)
    with open(args.failure) as f:
        failure_raw = json.load(f)
    failure_counts = dict(failure_raw)

    # simulation.log KO 计数（Gatling 3.11 为 TAB 分隔，KO 是独立字段：
    # REQUEST\t\tcreate_order_v1\t...\tKO\tjsonPath(...)）
    ko_count = 0
    if os.path.exists(args.sim_log):
        with open(args.sim_log, errors="replace") as f:
            ko_count = sum(1 for line in f if "\tKO\t" in line)

    # stats.json 结构（Gatling 3.11.5 实测）：stats.stats 是 dict（GROUP 节点，单请求场景即全部），
    # 计数在 numberOfRequests.{total,ok,ko}，百分位在 percentiles1/3.{total,ok,ko}
    req = stats.get("stats", {})
    total = req.get("numberOfRequests", {}).get("total", 0)
    ok = req.get("numberOfRequests", {}).get("ok", 0)
    ko_gatling = req.get("numberOfRequests", {}).get("ko", 0)
    p50 = req.get("percentiles1", {}).get("ok")
    p95 = req.get("percentiles3", {}).get("ok")

    no_json = int(failure_counts.pop("NO_JSON", 0))
    # "0" 是成功计数（respCode 0），pop 出后 business 桶只含真失败
    success_count = int(failure_counts.pop("0", 0))
    business_failures = sum(failure_counts.values())

    # 对账：业务失败 + NO_JSON 应 ≈ KO 总数（HTTP 层）
    reconciled = business_failures + no_json
    gap = ko_gatling - reconciled

    result = {
        "version": args.version,
        "concurrency": args.concurrency,
        "duration": args.duration,
        "gatling": {
            "total": total,
            "ok": ok,
            "ko": ko_gatling,
            "success_rate": round(ok / total, 4) if total else None,
            "p50_ms": p50,
            "p95_ms": p95,
        },
        "failures": {
            "business": failure_counts,
            "protocol_no_json": no_json,
            "sim_log_ko": ko_count,
            "reconciled": reconciled,
            "reconcile_gap": gap,
        },
        "order_diff": {
            "before": args.orders_before,
            "after": args.orders_after,
            "delta": args.orders_after - args.orders_before,
            "success_count": success_count,
        },
        "metrics": load_json(args.metrics) if os.path.exists(args.metrics) else {},
    }
    with open(args.out, "w") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
    print(f"结果已保存: {args.out}")
    print(f"  total={total} ok={ok} success_rate={result['gatling']['success_rate']} "
          f"p50={p50}ms p95={p95}ms ko={ko_gatling}")
    print(f"  业务失败={business_failures} protocol_no_json={no_json} "
          f"对账 gap={gap} 落库差额={result['order_diff']['delta']}")


if __name__ == "__main__":
    main()
