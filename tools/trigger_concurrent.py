#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 5 工具：对任务并发手动触发 POST /job/{id}/trigger，验证 SINGLE 互斥 + 行锁竞争。

两种模式：
  单任务模式（SINGLE 互斥主验证）：
    python trigger_concurrent.py --job-id 13501 --count 100 --concurrency 100
    预期：1 条分发（status=0 → 回调成功）+ 99 条 status=4 被阻塞（SINGLE，item5 拆分后独立状态）。
    同毫秒并发全部在 decide() 的 selectByIdForUpdate 行锁上排队 → wall 明显长于 A/B。

  多任务模式（行锁粒度 A/B）：并发触发 --count 个不同 job，每 job 1 次。
    python trigger_concurrent.py --job-ids 13601,13602,...,13700 --concurrency 100
    预期：各 job 自己一行锁，互不排队 → wall ≈ 单次 RTT，远短于单任务模式。
    证明行锁是 per-job（jobId）而非全局。

HTTP 返回恒为 code=200（trigger() 阻塞也在服务端落 status=4，不体现在响应码）；
被阻塞与否以 job_log 为准，用 analyze/DB 验证。wall/per-req 为行锁串行化佐证。
"""
import argparse
import time
from concurrent.futures import ThreadPoolExecutor

import requests

BASE = "http://localhost:8080"


def login():
    r = requests.post(f"{BASE}/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def fire(job_id, headers):
    t0 = time.time()
    r = requests.post(f"{BASE}/job/{job_id}/trigger", headers=headers, timeout=30)
    lat = time.time() - t0
    try:
        code = r.json().get("code")
    except Exception:
        code = f"http{r.status_code}"
    return job_id, lat, code


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--job-id", type=int, help="单任务模式：对该 job 并发打 --count 次")
    p.add_argument("--job-ids", help="多任务模式：逗号分隔 job id，每 job 并发打 1 次")
    p.add_argument("--count", type=int, default=100)
    p.add_argument("--concurrency", type=int, default=100)
    args = p.parse_args()

    if args.job_id:
        targets = [args.job_id] * args.count
        mode = f"single-job {args.job_id} x{args.count}"
    elif args.job_ids:
        targets = [int(x) for x in args.job_ids.split(",")]
        mode = f"multi-job {len(targets)} jobs x1"
    else:
        p.error("need --job-id or --job-ids")

    headers = login()
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        results = list(ex.map(lambda jid: fire(jid, headers), targets))
    wall = time.time() - t0

    codes = {}
    lat = []
    for _, l, c in results:
        codes[c] = codes.get(c, 0) + 1
        lat.append(l)
    lat.sort()
    p95 = lat[int(len(lat) * 0.95)]
    print(f"[{mode}] fired={len(targets)} wall={wall:.2f}s "
          f"per-req avg={sum(lat)/len(lat)*1000:.1f}ms p95={p95*1000:.1f}ms", flush=True)
    print(f"  response codes: {codes}", flush=True)


if __name__ == "__main__":
    main()
