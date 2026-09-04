#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对照：同一 1Hz job 开启期间，cron 与 manual 是否都落行。"""
import sys, time
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
job = dict(jobName="probe-1hz-b", jobGroupId=1, jobDesc="probe-b", handlerName="demoHandler",
           executorParam="", cron="*/1 * * * * ?", routeStrategy="round_robin",
           blockStrategy="", retryCount=0, timeout=0, triggerStatus=0)
jid = requests.post(f"{BASE}/job", headers=h, json=job, timeout=15).json()["data"]
after = q("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s",(jid,))[0][0]
requests.post(f"{BASE}/job/{jid}/start", headers=h, timeout=15)
print(f"job {jid} started @ {datetime.now():%H:%M:%S}, waiting 8s for cron fires...", flush=True)
time.sleep(8)
r = q("SELECT COUNT(*) FROM job_log WHERE job_id=%s AND id>%s", (jid, after))[0][0]
print(f"cron 8s 后落行数 = {r}", flush=True)
n = requests.post(f"{BASE}/job/{jid}/trigger", headers=h, timeout=15).json()
print(f"manual trigger resp code={n.get('code')} msg={n.get('msg')}", flush=True)
time.sleep(2)
r2 = q("SELECT id,status,trigger_type,handle_msg FROM job_log WHERE job_id=%s AND id>%s ORDER BY id", (jid, after))
print(f"manual 后总行数 = {len(r2)}", flush=True)
for x in r2:
    print(f"  log id={x[0]} st={x[1]} type={x[2]} msg={(x[3] or '')[:40]}", flush=True)
requests.post(f"{BASE}/job/{jid}/stop", headers=h, timeout=15)
requests.delete(f"{BASE}/job/{jid}", headers=h, timeout=15)
q("DELETE FROM job_log WHERE job_id=%s", (jid,))
print("cleaned", flush=True)
