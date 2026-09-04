#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开 MySQL general_log 数秒，抓 cron 触发路径实际到达的 SQL。"""
import sys, time
from datetime import datetime
import pymysql, requests
sys.path.insert(0, __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig

BASE = "http://localhost:8080"
DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          charset="utf8mb4", autocommit=True)

def q(sql, args=None, db=None):
    c = pymysql.connect(**{**DB, "database": db or "ww_job"})
    try:
        with c.cursor() as cur:
            cur.execute(sql, args or ()); return cur.fetchall()
    finally:
        c.close()

def login():
    r = requests.post(f"{BASE}/auth/login", json={"username":"admin","password":"admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}
h = login()

# 0) 清残留（上两轮崩溃的 job）
for jid in (47, 48):
    try:
        requests.post(f"{BASE}/job/{jid}/stop", headers=h, timeout=10)
        requests.delete(f"{BASE}/job/{jid}", headers=h, timeout=10)
        q("DELETE FROM job_log WHERE job_id=%s", (jid,))
    except Exception:
        pass
print("leftover cleaned", flush=True)

# 1) general_log 就绪
q("SET global log_output='TABLE'", db="mysql")
q("SET global general_log=OFF", db="mysql")
q("TRUNCATE TABLE mysql.general_log", db="mysql")
q("SET global general_log=ON", db="mysql")

# 2) 建 1Hz job + start，采集 6s
job = dict(jobName="sqlprobe-1hz", jobGroupId=1, jobDesc="sqlprobe", handlerName="demoHandler",
           executorParam="", cron="*/1 * * * * ?", routeStrategy="round_robin",
           blockStrategy="", retryCount=0, timeout=0, triggerStatus=0)
jid = requests.post(f"{BASE}/job", headers=h, json=job, timeout=15).json()["data"]
requests.post(f"{BASE}/job/{jid}/start", headers=h, timeout=15)
print(f"job {jid} started @ {datetime.now():%H:%M:%S}, collecting 6s...", flush=True)
time.sleep(6)

# 3) 关日志拉回
q("SET global general_log=OFF", db="mysql")
allrows = q("SELECT event_time, command_type, argument FROM mysql.general_log ORDER BY event_time", db="mysql")
def txt(v):
    return v.decode("utf-8", "replace") if isinstance(v, (bytes, bytearray)) else str(v)
def clean(sql):
    # 单行化 + 去掉重复空白
    return " ".join(txt(sql).split())
print(f"总 {len(allrows)} 行", flush=True)
targets = [(r[0], clean(r[2])) for r in allrows if "job_log" in txt(r[2]) or "job_info" in txt(r[2])]
print(f"=== job_log/job_info SQL（{len(targets)} 条）===", flush=True)
for t, s in targets:
    print(f"  {t.strftime('%H:%M:%S.%f')[:-3]} {s}", flush=True)

# 4) 清理
requests.post(f"{BASE}/job/{jid}/stop", headers=h, timeout=15)
requests.delete(f"{BASE}/job/{jid}", headers=h, timeout=15)
q("DELETE FROM job_log WHERE job_id=%s", (jid,))
q("SET global general_log=OFF", db="mysql")
print("cleaned, general_log OFF", flush=True)
