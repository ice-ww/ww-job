#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""hold 一个 1Hz job 持续 3 分钟，让 IDEA 控制台持续刷 ww-job-trigger 异常栈，供人工读取。"""
import sys, time
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
job = dict(jobName="probe-1hz-hold", jobGroupId=1, jobDesc="hold for trace", handlerName="demoHandler",
           executorParam="", cron="*/1 * * * * ?", routeStrategy="round_robin",
           blockStrategy="", retryCount=0, timeout=0, triggerStatus=0)
jid = requests.post(f"{BASE}/job", headers=h, json=job, timeout=15).json()["data"]
requests.post(f"{BASE}/job/{jid}/start", headers=h, timeout=15)
print(f"JOB {jid} RUNNING — 看 IDEA admin 控制台的 ww-job-trigger 异常栈", flush=True)
try:
    time.sleep(180)
finally:
    requests.post(f"{BASE}/job/{jid}/stop", headers=h, timeout=15)
    requests.delete(f"{BASE}/job/{jid}", headers=h, timeout=15)
    q("DELETE FROM job_log WHERE job_id=%s", (jid,))
    print("stopped+cleaned", flush=True)
