#!/usr/bin/env python3
# =============================================================================
# tfctl —— TicketFlow 研发/运维效能 CLI
#
# 目标（对应 JD「用代码定义效能：连接代码、构建、部署等数据孤岛」）：
#   把散落在 Prometheus / Kafka / Maven / benchmark 脚本里的信息，收敛成一个命令入口，
#   输出「下单链路体检报告」，替代人工在多终端里逐个查询。
#
# 设计取舍：
#   * 仅依赖 Python 标准库（urllib/subprocess/argparse），任何环境 python3 即可运行，
#     不引入 pip 依赖，保证 CI 与本机都能零安装执行。
#   * 所有外部系统地址可用环境变量覆盖（PROM_URL / KAFKA_CONTAINER / ORDER_GROUP）。
#
# 子命令：
#   diag      下单链路体检（Prometheus：up / 建单 / 扣减 / 落库率）
#   lag       Kafka 消费积压（docker exec kafka-consumer-groups）
#   deps      Maven 依赖冲突/重复（dependency:tree -Dverbose）
#   bench     调用 benchmark 脚本跑冒烟/阶梯压测
#
# 用法示例：
#   python3 tools/tfctl/tfctl.py diag
#   python3 tools/tfctl/tfctl.py lag
#   python3 tools/tfctl/tfctl.py deps
#   python3 tools/tfctl/tfctl.py bench --version v4 --rate 40 --duration 30
# =============================================================================
import argparse
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

PROM_URL = os.environ.get("PROM_URL", "http://localhost:9090")
KAFKA_CONTAINER = os.environ.get("KAFKA_CONTAINER", "ticketflow-kafka")
KAFKA_BOOTSTRAP = os.environ.get("KAFKA_BOOTSTRAP", "127.0.0.1:9092")
ORDER_GROUP = os.environ.get("ORDER_GROUP", "order-service")
CREATE_TOPIC = os.environ.get("CREATE_TOPIC", "create_order")


def prom_query(expr, base=PROM_URL):
    """执行一次即时查询，返回 result 列表；失败抛异常由调用方处理。"""
    url = base.rstrip("/") + "/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    with urllib.request.urlopen(url, timeout=8) as resp:
        return json.load(resp)["data"]["result"]


def scalar(expr, default=None):
    try:
        result = prom_query(expr)
    except Exception:
        return default
    if not result:
        return default
    try:
        return float(result[0]["value"][1])
    except (KeyError, IndexError, ValueError):
        return default


def fmt(value, suffix="", digits=3):
    if value is None:
        return "N/A"
    return f"{value:.{digits}f}{suffix}"


def cmd_diag(args):
    print(f"TicketFlow 下单链路体检  (prom={PROM_URL})")
    print("-" * 60)

    total = scalar('count(up{job="spring-boot"})')
    alive = scalar('count(up{job="spring-boot"} == 1)')
    print(f"服务存活            : {fmt(alive, digits=0)}/{fmt(total, digits=0)}")

    created = scalar('sum(rate(ticketflow_order_created_total[5m]))')
    deduct_ok = scalar('sum(rate(ticketflow_program_stock_deduct_total{result="success"}[5m]))')
    fail = scalar('sum(rate(ticketflow_order_create_fail_total[5m]))')

    print(f"建单速率(5m)        : {fmt(created, '/s')}")
    print(f"扣减成功速率(5m)    : {fmt(deduct_ok, '/s')}")
    print(f"建单失败速率(5m)    : {fmt(fail, '/s')}")

    if created is not None and deduct_ok and deduct_ok > 0:
        print(f"端到端落库率        : {created / deduct_ok * 100:.2f}%   (created/deduct_ok)")
    else:
        print("端到端落库率        : N/A（无扣减数据，可能无流量或采集缺失）")

    if not alive:
        print("\n提示：Prometheus 无 spring-boot 目标数据，先确认服务已启动且被采集。")


def cmd_lag(args):
    cmd = [
        "docker", "exec", KAFKA_CONTAINER, "kafka-consumer-groups.sh",
        "--bootstrap-server", KAFKA_BOOTSTRAP,
        "--describe", "--group", ORDER_GROUP,
    ]
    try:
        out = subprocess.check_output(cmd, text=True, timeout=25)
    except Exception as exc:  # noqa: BLE001 - CLI 场景需给出可读提示
        print(f"无法获取 Kafka lag: {exc}")
        print(f"提示：确认容器 {KAFKA_CONTAINER} 在运行，或设置 KAFKA_CONTAINER/ORDER_GROUP。")
        return

    # 列顺序：GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
    # 先按表头里的 LAG 列定位；找不到表头时回退到第 6 列（0-based 5）。
    lag_idx = 5
    total = 0
    rows = 0
    for line in out.splitlines():
        parts = line.split()
        if not parts:
            continue
        if "LAG" in parts:
            lag_idx = parts.index("LAG")
            continue
        if len(parts) > lag_idx and len(parts) >= 2 and parts[1] == CREATE_TOPIC:
            try:
                lag = int(parts[lag_idx])
            except ValueError:
                continue
            total += max(lag, 0)
            rows += 1
    print(f"group={ORDER_GROUP} topic={CREATE_TOPIC} partitions={rows} total_lag={total}")
    if rows == 0:
        print("（未找到该 topic 的消费记录，可能服务未启动或 group 名不同）")


def cmd_deps(args):
    print("运行 mvn dependency:tree -Dverbose（可能需要数十秒）...")
    proc = subprocess.run(
        ["mvn", "-B", "dependency:tree", "-Dverbose"],
        capture_output=True, text=True, cwd=args.repo_root,
    )
    conflicts = [
        line.strip() for line in proc.stdout.splitlines()
        if "omitted for conflict" in line or "omitted for duplicate" in line
    ]
    print(f"依赖冲突/重复条目: {len(conflicts)}")
    for line in conflicts[: args.limit]:
        print("  " + line)
    if len(conflicts) > args.limit:
        print(f"  ...（仅显示前 {args.limit} 条）")


def cmd_bench(args):
    script = os.path.join(args.repo_root, "benchmark", "scripts", "run-single.sh")
    cmd = [
        "bash", script, args.version, "0", str(args.duration),
        "openloop", str(args.rate), str(args.rounds),
    ]
    print("执行:", " ".join(cmd))
    subprocess.run(cmd, check=False, cwd=args.repo_root)


def build_parser():
    parser = argparse.ArgumentParser(prog="tfctl", description="TicketFlow 效能 CLI")
    parser.add_argument(
        "--repo-root", default=os.getcwd(),
        help="仓库根目录（默认当前目录），供 deps/bench 使用",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_diag = sub.add_parser("diag", help="下单链路体检（Prometheus）")
    p_diag.set_defaults(func=cmd_diag)

    p_lag = sub.add_parser("lag", help="Kafka 消费积压")
    p_lag.set_defaults(func=cmd_lag)

    p_deps = sub.add_parser("deps", help="依赖冲突/重复检查")
    p_deps.add_argument("--limit", type=int, default=50)
    p_deps.set_defaults(func=cmd_deps)

    p_bench = sub.add_parser("bench", help="调用 benchmark 冒烟/压测")
    p_bench.add_argument("--version", default="v4")
    p_bench.add_argument("--rate", type=int, default=40)
    p_bench.add_argument("--duration", type=int, default=30)
    p_bench.add_argument("--rounds", type=int, default=1)
    p_bench.set_defaults(func=cmd_bench)

    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    args.func(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
