#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
压测观测脚本（Phase 3 慢任务用）：每 5s 采样 job_log 总量 + status=0（执行中）数量，
输出密度与"同时运行任务数"峰值，并打印窗口边界供 analyze_window 复用。

用法：
  python observe_concurrent.py --window-min 3 --label D50_slow
"""
import argparse
import time
from datetime import datetime

import requests

BASE = "http://localhost:8080"


def login():
    r = requests.post(f"{BASE}/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def get_total(headers, status=None):
    params = {"current": 1, "size": 1}
    if status is not None:
        params["status"] = status
    r = requests.get(f"{BASE}/joblog/page", params=params, headers=headers, timeout=10)
    return r.json().get("total", 0)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--window-min", type=float, default=3.0)
    p.add_argument("--label", default="")
    args = p.parse_args()

    headers = login()
    t0 = datetime.now()
    prev_total = get_total(headers)
    prev_running = get_total(headers, status=0)
    max_running = prev_running
    print(f"[obsC] label={args.label} t0={t0.strftime('%Y-%m-%d %H:%M:%S')} "
          f"total={prev_total} running={prev_running}", flush=True)
    n = max(1, int(args.window_min * 60 / 5))
    for i in range(n):
        time.sleep(5)
        total = get_total(headers)
        running = get_total(headers, status=0)
        delta = total - prev_total
        max_running = max(max_running, running)
        print(f"  t+{(i + 1) * 5:4d}s total={total} delta={delta:5d} density~{delta / 5.0:.1f}/s "
              f"running={running} (max={max_running})", flush=True)
        prev_total = total
    t1 = datetime.now()
    print(f"[obsC] t1={t1.strftime('%Y-%m-%d %H:%M:%S')} max_running={max_running}", flush=True)
    print(f"[obsC] WINDOW_BEGIN={t0.strftime('%Y-%m-%d %H:%M:%S')} WINDOW_END={t1.strftime('%Y-%m-%d %H:%M:%S')}", flush=True)


if __name__ == "__main__":
    main()
