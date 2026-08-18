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
import re
import sys


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--label", required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--concurrency", type=int, required=True)
    p.add_argument("--rate", type=int, default=0)
    p.add_argument("--mode", default="closed")
    p.add_argument("--duration", type=int, required=True)
    p.add_argument("--stats", required=True)
    p.add_argument("--failure", required=True)
    p.add_argument("--sim-log", required=True)
    p.add_argument("--orders-before", type=int, required=True)
    p.add_argument("--orders-after", type=int, required=True)
    p.add_argument("--metrics", required=True)
    p.add_argument("--snapshot", default=None, help="配置快照 json（run-single.sh 生成）")
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

    # 失败分类以 simulation.log 为准（Gatling 3.11 TAB 分隔，KO 行 message 含业务码）：
    # 全量压测实测 OrderResultCounter 有结构性丢失——用户 END 时飞行中的请求计入 stats，
    # 但其后的 record exec 不再执行（每轮缺 ≤ 并发数条），导致 failure-*.json 不闭合。
    # simulation.log 由 Gatling 引擎写入，KO 计数与 stats.ko 完全一致，100% 闭合。
    # 无业务码的 KO（超时/连接错误）归 protocol_no_json。
    biz_codes = {}
    no_json = 0
    ko_count = 0
    if os.path.exists(args.sim_log):
        with open(args.sim_log, errors="replace") as f:
            for line in f:
                if "\tKO\t" not in line:
                    continue
                ko_count += 1
                m = re.search(r"but actually found (-?\d+)", line)
                if m:
                    code = m.group(1)
                    biz_codes[code] = biz_codes.get(code, 0) + 1
                else:
                    no_json += 1

    # stats.json 结构（Gatling 3.11.5 实测）：stats.stats 是 dict（GROUP 节点，单请求场景即全部），
    # 计数在 numberOfRequests.{total,ok,ko}，百分位在 percentiles1/3/4.{total,ok,ko}
    #   percentiles1=p50  percentiles3=p95  percentiles4=p99
    req = stats.get("stats", {})
    total = req.get("numberOfRequests", {}).get("total", 0)
    ok = req.get("numberOfRequests", {}).get("ok", 0)
    ko_gatling = req.get("numberOfRequests", {}).get("ko", 0)
    p50 = req.get("percentiles1", {}).get("ok")
    p95 = req.get("percentiles3", {}).get("ok")
    p99 = req.get("percentiles4", {}).get("ok")
    p_max = req.get("maxResponseTime", {}).get("ok")

    # 成功计数以 stats.ok 为准（同上了 in-flight 丢失，counter 的 "0" 仅作参考）
    success_count = ok
    business_failures = sum(biz_codes.values())
    counter_ref = {k: v for k, v in failure_raw.items() if k not in ("0", "NO_JSON")}

    # 对账：业务失败 + NO_JSON 应 ≈ KO 总数（HTTP 层）
    reconciled = business_failures + no_json
    gap = ko_gatling - reconciled

    # 落库差额（消费排空后统计）：异步版本（V4/V5）的端到端成功率 = 落库数/请求数
    order_delta = args.orders_after - args.orders_before
    end_to_end_success_rate = round(order_delta / total, 4) if total else None

    result = {
        "version": args.version,
        "mode": args.mode,
        "concurrency": args.concurrency,
        "rate": args.rate,
        "duration": args.duration,
        "gatling": {
            "total": total,
            "ok": ok,
            "ko": ko_gatling,
            "success_rate": round(ok / total, 4) if total else None,
            "p50_ms": p50,
            "p95_ms": p95,
            "p99_ms": p99,
            "max_ms": p_max,
        },
        "failures": {
            "business": biz_codes,
            "protocol_no_json": no_json,
            "limit_over_50009": biz_codes.get("50009", 0),
            "counter_ref": counter_ref,
            "sim_log_ko": ko_count,
            "reconciled": reconciled,
            "reconcile_gap": gap,
        },
        "order_diff": {
            "before": args.orders_before,
            "after": args.orders_after,
            "delta": order_delta,
            "success_count": success_count,
            # 端到端成功率 = 落库数/请求数（异步架构主指标；受理成功率见 gatling.success_rate）
            "end_to_end_success_rate": end_to_end_success_rate,
        },
        "metrics": load_json(args.metrics) if os.path.exists(args.metrics) else {},
        "snapshot": load_json(args.snapshot) if args.snapshot and os.path.exists(args.snapshot) else {},
    }
    with open(args.out, "w") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
    print(f"结果已保存: {args.out}")
    print(f"  total={total} ok={ok} 受理成功率={result['gatling']['success_rate']} "
          f"端到端成功率(落库率)={end_to_end_success_rate} p50={p50}ms p95={p95}ms p99={p99}ms max={p_max}ms ko={ko_gatling}")
    print(f"  业务失败={business_failures} protocol_no_json={no_json} "
          f"对账 gap={gap} 落库差额={order_delta}")


if __name__ == "__main__":
    main()
