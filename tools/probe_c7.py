#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""取证：单任务 1Hz cron 的真实触发节拍。
逐秒读 job_info.trigger_next_time + 每 2s 数 job_log 行，不清理，30s 后停任务。
"""
import sys, time, threading
from datetime import datetime
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

h = login()
# 建任务
job = dict(jobName="probe-1hz", jobGroupId=1, jobDesc="probe", handlerName="demoHandler",
           executorParam="", cron="*/1 * * * * ?", routeStrategy="round_robin",
           blockStrategy="", retryCount=0, timeout=0, triggerStatus=0)
jid = requests.post(f"{BASE}/job", headers=h, json=job, timeout=15).json()["data"]
after = q("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s",(jid,))[0][0]
requests.post(f"{BASE}/job/{jid}/start", headers=h, timeout=15)
print(f"job {jid} started @ {datetime.now():%H:%M:%S}")

start = time.time()
last_cnt = 0
probe_t = None
while time.time() - start < 30:
    r = q1 = q("SELECT trigger_next_time, trigger_status, id FROM job_info WHERE id=%s", (jid,))[0]
    nxt = r[0]
    nxt_s = datetime.fromtimestamp(nxt/1000).strftime("%H:%M:%S.%f")[:-3] if nxt else None
    rows = q("SELECT id,status,trigger_time FROM job_log WHERE job_id=%s AND id>%s ORDER BY id", (jid, after))
    now_s = datetime.now().strftime("%H:%M:%S")
    if len(rows) != last_cnt:
        new = rows[last_cnt:]
        for x in new:
            print(f"  [{now_s}] +log id={x[0]} st={x[1]} trigger_at={x[2]}", flush=True)
        last_cnt = len(rows)
    if probe_t != nxt_s:
        print(f"  [{now_s}] next_time={nxt_s} status={r[1]} rows={len(rows)}", flush=True)
        probe_t = nxt_s
    time.sleep(0.5)
print(f"== 30s 共 {last_cnt} 条触发, next_time 最后={datetime.fromtimestamp(q1[0][0]/1000).strftime('%H:%M:%S.%f')[:-3] if q1[0] else None}")
requests.post(f"{BASE}/job/{jid}/stop", headers=h, timeout=15)
requests.delete(f"{BASE}/job/{jid}", headers=h, timeout=15)
q("DELETE FROM job_log WHERE job_id=%s", (jid,))
print("cleaned")
