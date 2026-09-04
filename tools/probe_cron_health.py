#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""停掉残留 job 44，分析其 133 条 cron 行的时间间隔，量化触发率/饿死振荡。"""
import sys
from datetime import datetime, timedelta
import pymysql, requests
sys.path.insert(0, __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig

BASE = "http://localhost:8080"
DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          database="ww_job", charset="utf8mb4", autocommit=True)

def q(sql, args=None):
    c = pymysql.connect(**DB)
    try:
        with c.cursor() as cur:
            cur.execute(sql, args or ()); return cur.fetchall()
    finally:
        c.close()

def login():
    r = requests.post(f"{BASE}/auth/login", json={"username":"admin","password":"admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}

# 1) 停掉残留 job 44
h = login()
try:
    requests.post(f"{BASE}/job/44/stop", headers=h, timeout=10)
    print("job 44 stopped", flush=True)
except Exception as e:
    print(f"stop 44: {e}", flush=True)

# 2) 分析 job 44 的行
rows = q("SELECT trigger_time FROM job_log WHERE job_id=44 AND trigger_type='cron' ORDER BY trigger_time")
print(f"job 44 共 {len(rows)} 条 cron 行", flush=True)
if len(rows) > 1:
    prev = None
    gap_counts = {}
    run_start = None; longest_run = 0; cur_run = 0; total_run = 0; runs = 0
    for r in rows:
        t = r[0]
        if prev:
            gap = (t - prev).total_seconds()
            key = f"{int(gap)}s" if gap < 60 else f"{int(gap/60)}m"
            gap_counts[key] = gap_counts.get(key, 0) + 1
        prev = t
    print("相邻触发间隔分布:", flush=True)
    for k in sorted(gap_counts, key=lambda x: float(x.rstrip('sm'))):
        print(f"  gap {k}: {gap_counts[k]} 次", flush=True)
    # 首末时间
    print(f"  首行 {rows[0][0]}, 末行 {rows[-1][0]}", flush=True)

# 3) 清理 job 44 及其日志
q("DELETE FROM job_log WHERE job_id=44")
try:
    requests.delete(f"{BASE}/job/44", headers=h, timeout=10)
except Exception:
    pass
print("job 44 deleted + logs cleaned", flush=True)
