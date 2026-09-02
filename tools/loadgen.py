#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ww-job 压测 driver：批量建任务，精确控制触发密度。

用法：
  python loadgen.py create <D> <K> [--base URL] [--group ID] [--param P] [--dry-run]
  python loadgen.py stop  [--base URL] [--group ID]   # 停掉 group 下所有 triggerStatus=1 的任务

原理（plan §4.2）：
  目标密度 D(次/秒)，每任务触发间隔 K(秒) → 任务数 N = D*K，
  任务 i 的 cron = "{i % K}/{K} * * * * ?"，秒位 i%K 让同窗口内各任务错开，无扎堆。
"""
import argparse
import json
import sys
import time

import requests

DEFAULT_BASE = "http://localhost:8080"
USERNAME = "admin"
PASSWORD = "admin123"


def login(base):
    r = requests.post(f"{base}/auth/login",
                      json={"username": USERNAME, "password": PASSWORD}, timeout=10)
    r.raise_for_status()
    token = r.json()["data"]["token"]
    return {"Authorization": f"Bearer {token}"}


def create_job(base, headers, job_group_id, handler, cron, param):
    body = {
        "jobGroupId": job_group_id,
        "jobName": "loadtest",
        "jobDesc": "load test job",
        "handlerName": handler,
        "executorParam": param,
        "cron": cron,
        "routeStrategy": "round",
        "retryCount": 0,
        "blockStrategy": "SINGLE",
        "triggerStatus": 1,
    }
    r = requests.post(f"{base}/job", json=body, headers=headers, timeout=10)
    if r.status_code != 200 or r.json().get("code") != 200:
        raise RuntimeError(f"create job failed at {cron}: HTTP {r.status_code} {r.text}")
    return r.json()["data"]


def cmd_create(args):
    D = args.density
    K = args.interval
    N = D * K
    headers = login(args.base)
    print(f"density D={D}/s, interval K={K}s -> tasks N={N}, group={args.group}, "
          f"param={args.param!r}", flush=True)
    if args.dry_run:
        print(f"[dry-run] would create {N} tasks with cron '{{i%K}}/{K} * * * * ?'")
        return
    for i in range(N):
        cron = f"{i % K}/{K} * * * * ?"
        create_job(args.base, headers, args.group, "loadTestHandler", cron, args.param)
        if i % 500 == 0:
            print(f"  created {i}/{N} {time.strftime('%H:%M:%S')}", flush=True)
    print(f"done, N={N}", flush=True)


def collect_enabled(base, headers, group_id):
    """健壮分页收集 group 下全部 triggerStatus=1 的任务 id。
    - 总页数从第一页的 pages 字段确定，固定遍历 1..total_pages，杜绝"空页提前 break 漏页"；
    - 单页抓取失败重试 3 次，仍失败则抛错（响亮失败，绝不静默截断）。"""
    page1 = None
    for attempt in range(3):
        try:
            page1 = requests.get(f"{base}/job/page",
                                 params={"page": 1, "size": 100, "jobGroupId": group_id},
                                 headers=headers, timeout=20)
            break
        except Exception:
            if attempt == 2:
                raise
            time.sleep(1)
    data = page1.json()
    total_pages = data.get("pages") or 1
    jobs = {}
    for j in (data.get("records") or []):
        if j.get("triggerStatus") == 1:
            jobs[j["id"]] = j
    for page in range(2, total_pages + 1):
        records = None
        for attempt in range(3):
            try:
                r = requests.get(f"{base}/job/page",
                                 params={"page": page, "size": 100, "jobGroupId": group_id},
                                 headers=headers, timeout=20)
                records = r.json().get("records") or []
                break
            except Exception:
                if attempt == 2:
                    raise RuntimeError(f"job page {page}/{total_pages} fetch failed after 3 attempts")
                time.sleep(1)
        for j in records:
            if j.get("triggerStatus") == 1:
                jobs[j["id"]] = j
    return sorted(jobs.keys())


def cmd_stop(args):
    """停掉 group 下所有已启用任务，让该档结束。

    F2-9 背景：/job/{id}/stop 端点无条件置 triggerStatus=0 且永远返回 ReturnT.success()，
    响应码无法检出真实失败；更糟的是 dispatchOne/dispatch 里用旧实体 updateById(job)，
    stop 若落在 in-flight 触发的写回窗口内，旧 triggerStatus=1 会被写回 → 任务"复活"。
    所以本工具改为：循环【全量停 → 等 5s（让 in-flight 触发排空）→ 重扫】，直到启用数为 0。
    复活的任务会在下一轮被重新停掉，天然收敛。"""
    headers = login(args.base)
    iteration = 0
    total_issued = 0
    while True:
        iteration += 1
        enabled = collect_enabled(args.base, headers, args.group)
        if not enabled:
            break
        print(f"[{time.strftime('%H:%M:%S')}] iter{iteration}: {len(enabled)} enabled, stopping...", flush=True)
        for jid in enabled:
            try:
                sr = requests.post(f"{args.base}/job/{jid}/stop", headers=headers, timeout=20)
                sr.raise_for_status()
            except Exception:
                # 失败不致命：下一轮重扫会再处理它
                pass
        total_issued += len(enabled)
        print(f"  waiting 5s for in-flight triggers to drain...", flush=True)
        time.sleep(5)
    print(f"done: 0 enabled; {total_issued} stop POSTs across {iteration} iteration(s) in group {args.group}", flush=True)


def main():
    p = argparse.ArgumentParser(description="ww-job load test driver")
    sub = p.add_subparsers(dest="cmd", required=True)

    pc = sub.add_parser("create", help="批量建任务达到目标密度")
    pc.add_argument("density", type=int, help="目标密度 D(次/秒)")
    pc.add_argument("interval", type=int, help="每任务触发间隔 K(秒)")
    pc.add_argument("--base", default=DEFAULT_BASE)
    pc.add_argument("--group", type=int, default=1, help="job_group id（默认 1 = sample-executor）")
    pc.add_argument("--param", default="", help="LoadTestHandler 参数：空/0=快任务，正整数=sleep ms，fail=抛异常")
    pc.add_argument("--dry-run", action="store_true", help="只打印将建的任务数，不真正建")
    pc.set_defaults(func=cmd_create)

    ps = sub.add_parser("stop", help="停掉 group 下所有任务")
    ps.add_argument("--base", default=DEFAULT_BASE)
    ps.add_argument("--group", type=int, default=1)
    ps.set_defaults(func=cmd_stop)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
