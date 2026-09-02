#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
压测观测窗口分析：拉取 joblog 中 trigger_time ∈ [begin, end) 的全部行，聚合输出：
  - 窗口内行数 / 密度 / status 分布
  - 每秒触发数（min/avg/max，含空秒数）
  - 覆盖的 distinct job_id 数（正确性：该档是否所有任务都被触发）
  - 每任务触发间隔百分位（p50/p90/max，看密度饱和导致的拉伸）

用法：
  python analyze_window.py --begin "2026-09-02 01:16:16" --end "2026-09-02 01:19:16" --label D300_pool30_clean

说明：
  /joblog/page 无时间过滤，只能按 id DESC 从最新往回拉，拉到某页最新 trigger_time < begin 即停。
  建议在档结束后（已 drain 到 0）再跑，得到稳定快照。
"""
import argparse
import collections
import statistics
import time
from datetime import datetime

import requests

BASE = "http://localhost:8080"


def login():
    r = requests.post(f"{BASE}/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def fetch_pages(headers, begin_dt):
    """从最新往回拉 joblog 页，直到一页里最新 trigger_time < begin。返回全部原始行。"""
    rows = []
    page = 1
    while True:
        recs = None
        for attempt in range(3):
            try:
                r = requests.get(f"{BASE}/joblog/page", params={"page": page, "size": 100},
                                 headers=headers, timeout=20)
                recs = r.json().get("records") or []
                break
            except Exception:
                if attempt == 2:
                    raise RuntimeError(f"joblog page {page} fetch failed after 3 attempts")
                time.sleep(1)
        if not recs:
            break
        rows.extend(recs)
        newest = recs[0].get("triggerTime")
        if newest and datetime.fromisoformat(newest.replace("Z", "+00:00")) < begin_dt:
            break
        page += 1
        if page % 100 == 0:
            print(f"  fetched {len(rows)} rows (page {page})...", flush=True)
    return rows


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--begin", required=True, help="窗口起点 YYYY-MM-DD HH:MM:SS")
    p.add_argument("--end", required=True, help="窗口终点 YYYY-MM-DD HH:MM:SS")
    p.add_argument("--label", default="")
    args = p.parse_args()
    begin_dt = datetime.strptime(args.begin, "%Y-%m-%d %H:%M:%S")
    end_dt = datetime.strptime(args.end, "%Y-%m-%d %H:%M:%S")
    win_secs = (end_dt - begin_dt).total_seconds()

    headers = login()
    rows = fetch_pages(headers, begin_dt)
    print(f"[analyze] fetched {len(rows)} raw rows, filtering window...", flush=True)

    # 客户端过滤：trigger_time ∈ [begin, end)
    in_win = []
    for row in rows:
        tt = row.get("triggerTime")
        if not tt:
            continue
        try:
            dt = datetime.fromisoformat(tt.replace("Z", "+00:00"))
        except ValueError:
            continue
        if begin_dt <= dt < end_dt:
            in_win.append((row, dt))
    in_win.sort(key=lambda x: x[1])

    n = len(in_win)
    print(f"label={args.label} window={args.begin}~{args.end} ({win_secs:.0f}s) rows={n} "
          f"density={n / win_secs:.1f}/s", flush=True)

    # status 分布
    status = collections.Counter(r.get("status") for r, _ in in_win)
    print("status:", dict(status), "(0=执行中 1=成功 2=失败 3=超时/被阻塞)", flush=True)

    # 跨秒率：执行过的行里 handle_time != trigger_time 的占比。
    # 纯快任务基线 ~6%（时间轮 +TICK_MS 调度延迟）；共享池队头阻塞会让快任务真实排队等慢任务 → 暴涨。
    cross = 0
    have_ht = 0
    for r, _ in in_win:
        ht = r.get("handleTime")
        if not ht:
            continue
        have_ht += 1
        if ht != r.get("triggerTime"):
            cross += 1
    if have_ht:
        print(f"cross-second: {cross}/{have_ht} = {100 * cross / have_ht:.1f}% (handleTime!=triggerTime, 秒精度)", flush=True)

    # 每秒计数
    per_sec = collections.Counter(r[1].strftime("%Y-%m-%d %H:%M:%S") for r in in_win)
    vals = list(per_sec.values())
    empty_secs = int(win_secs) - len(per_sec)
    if vals:
        print(f"per-sec: min={min(vals)} avg={sum(vals) / len(vals):.1f} max={max(vals)} "
              f"empty_secs={empty_secs}", flush=True)
    else:
        print("per-sec: no rows in window", flush=True)

    # distinct job_id
    distinct = {r.get("jobId") for r, _ in in_win}
    print(f"distinct job_id: {len(distinct)}", flush=True)

    # 每任务触发间隔
    if n > 1:
        by_job = collections.defaultdict(list)
        for r, dt in in_win:
            by_job[r.get("jobId")].append(dt)
        gaps = []
        for jid, dts in by_job.items():
            dts.sort()
            for a, b in zip(dts, dts[1:]):
                gaps.append((b - a).total_seconds())
        if gaps:
            gaps.sort()
            p50 = gaps[int(len(gaps) * 0.50)]
            p90 = gaps[int(len(gaps) * 0.90)]
            print(f"per-job interval: p50={p50:.0f}s p90={p90:.0f}s max={gaps[-1]:.0f}s "
                  f"(n_gaps={len(gaps)})", flush=True)


if __name__ == "__main__":
    main()
