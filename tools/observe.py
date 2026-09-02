#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
压测观测脚本：在稳定观测窗口内每 10s 采样 job_log 总量算密度，结束时打印
显式时间边界（host 时钟 = 上海时间，供 SQL 用显式 trigger_time 边界重查 status/空秒/P99）。

用法：
  python observe.py --window-min 3 --label D50
  输出末尾 WINDOW_BEGIN / WINDOW_END 即为观测窗口边界。
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


def get_total(headers):
    r = requests.get(f"{BASE}/joblog/page", params={"current": 1, "size": 1},
                     headers=headers, timeout=10)
    return r.json().get("total", 0)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--window-min", type=float, default=3.0)
    p.add_argument("--label", default="")
    args = p.parse_args()

    headers = login()
    t0 = datetime.now()
    prev = get_total(headers)
    print(f"[observe] label={args.label} t0={t0.strftime('%Y-%m-%d %H:%M:%S')} total={prev}", flush=True)
    n = max(1, int(args.window_min * 60 / 10))
    for i in range(n):
        time.sleep(10)
        now = get_total(headers)
        delta = now - prev
        print(f"  t+{(i + 1) * 10:3d}s total={now} delta={delta:5d} density~{delta / 10.0:.1f}/s", flush=True)
        prev = now
    t1 = datetime.now()
    print(f"[observe] t1={t1.strftime('%Y-%m-%d %H:%M:%S')}", flush=True)
    print(f"[observe] WINDOW_BEGIN={t0.strftime('%Y-%m-%d %H:%M:%S')} WINDOW_END={t1.strftime('%Y-%m-%d %H:%M:%S')}", flush=True)


if __name__ == "__main__":
    main()
