#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P1 Stage A 写放大探针：开 mysql.general_log 抓 K 次「手动触发 demoHandler(round_robin, 非SINGLE)」的实达 SQL，
按 job_info / job_log / job_registry 分类统计 → 断言 P1-SA 收敛契约：
  - job_info:   2 读（selectById + decide selectByIdForUpdate）+ 1 写（ack touch_last_time 窄更新）
  - job_log:    1 写（INSERT 带 executor_address）+ 1 写（回调 completeById 条件窄更新 WHERE status IN(0,3)）
  - job_registry: 1 读（route）
  - 契约断言：job_info+job_log 每触发 ∈ [5,6]；无全行 UPDATE job_log 补写地址；无整行 UPDATE job_info（除 touch_last_time/advance）
凭据复用 registry_unique_key_migration（不打印）。清理走 finally，崩溃也不留残。
"""
import sys
import time
from collections import Counter

import pymysql
import requests

sys.path.insert(0, __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

BASE = "http://localhost:8080"
DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          charset="utf8mb4", autocommit=True)
K = 4  # 手动触发次数


def q(sql, args=None, db=None):
    c = pymysql.connect(**{**DB, "database": db or "ww_job"})
    try:
        with c.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.fetchall()
    finally:
        c.close()


def txt(v):
    return v.decode("utf-8", "replace") if isinstance(v, (bytes, bytearray)) else str(v)


def clean(s):
    return " ".join(txt(s).split())


def login():
    r = requests.post(f"{BASE}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def terminal(jid, after_id, timeout_s=10):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        r = q1("SELECT id,status FROM job_log WHERE job_id=%s AND id>%s ORDER BY id DESC LIMIT 1", (jid, after_id))
        if r and r[1] != 0:
            return r
        time.sleep(0.4)
    return None


def q1(sql, args=None):
    r = q(sql, args)
    return r[0] if r else None


h = login()
jid = None
try:
    # --- general_log 就绪 ---
    q("SET global log_output='TABLE'", db="mysql")
    q("SET global general_log=OFF", db="mysql")
    q("TRUNCATE TABLE mysql.general_log", db="mysql")

    # --- 建 scratch job（round_robin + demoHandler，不开 cron）---
    job = dict(jobName="p1-sa-writeamp", jobGroupId=1, jobDesc="p1 stage-a write-amp probe",
               handlerName="demoHandler", executorParam="", cron="0 0 0 1 1 ?",
               routeStrategy="round_robin", blockStrategy="", retryCount=0, timeout=0, triggerStatus=0)
    jid = requests.post(f"{BASE}/job", headers=h, json=job, timeout=15).json()["data"]
    base_id = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s", (jid,))[0]

    # --- 采集窗内连发 K 次手动触发，各等终态 ---
    q("SET global general_log=ON", db="mysql")
    for i in range(K):
        requests.post(f"{BASE}/job/{jid}/trigger", headers=h, timeout=15)
        t = terminal(jid, base_id)
        assert t is not None, f"第 {i+1} 次触发 {timeout_s}s 内未到终态"
        base_id = t[0]
        time.sleep(0.2)
    q("SET global general_log=OFF", db="mysql")

    # --- 拉回并按三表分类 ---
    allrows = q("SELECT event_time, command_type, argument FROM mysql.general_log ORDER BY event_time", db="mysql")
    stmts = []
    for r in allrows:
        s = clean(r[2])
        if any(t in s for t in ("job_info", "job_log", "job_registry")):
            stmts.append((r[0], s))
    print(f"\n采集窗 {K} 次手动触发，job_info/job_log/job_registry 相关 SQL 共 {len(stmts)} 条：", flush=True)
    for t, s in stmts:
        print(f"  {t.strftime('%H:%M:%S.%f')[:-3]}  {s}", flush=True)

    # --- 分类统计 ---
    # 触发热路径的 job_info 读 = selectById + decide 的 selectByIdForUpdate，都含 "FROM job_info WHERE id"；
    # 调度线程 scheduleLoop 每秒的预读扫描（WHERE trigger_status=1 AND trigger_next_time<=...）与触发无关，单独计为背景噪音
    trigger_info_reads = sum(1 for _, s in stmts if "FROM job_info WHERE id" in s and s.strip().upper().startswith("SELECT"))
    scan_noise = sum(1 for _, s in stmts if "FROM job_info" in s and "trigger_status" in s and "trigger_next_time" in s)
    info_reads = trigger_info_reads
    info_writes = sum(1 for _, s in stmts if "UPDATE job_info" in s)
    log_inserts = sum(1 for _, s in stmts if "INSERT" in s and "job_log" in s)
    log_writes = sum(1 for _, s in stmts if "UPDATE job_log" in s)
    reg = sum(1 for _, s in stmts if "job_registry" in s)
    # 契约违反检查
    full_log_addr_update = [s for _, s in stmts if "UPDATE job_log" in s and "executor_address =" in s
                            and "WHERE" in s and "status" not in s.lower()]
    full_info_update = [s for _, s in stmts if "UPDATE job_info" in s
                        and "trigger_last_time" not in s and "trigger_next_time" not in s]
    core_per_trigger = (info_reads + info_writes + log_inserts + log_writes) / K
    info_reads_per = info_reads / K
    info_writes_per = info_writes / K

    print(f"\n分类（K={K}）：job_info 读 {info_reads}（{info_reads_per:.1f}/触发；调度扫描背景 {scan_noise} 条剔除）"
          f"| job_info 写 {info_writes}（{info_writes_per:.1f}/触发）"
          f"| job_log INSERT {log_inserts}（{log_inserts/K:.1f}/触发）| job_log UPDATE {log_writes}（{log_writes/K:.1f}/触发）"
          f"| job_registry {reg}", flush=True)

    ok = True
    # 精确契约：job_info 读=2/触发、job_info 写=1/触发、job_log INSERT=1、job_log UPDATE=1 → core=5/触发
    def chk(cond, msg):
        global ok
        print(("PASS  " if cond else "FAIL  ") + msg, flush=True)
        ok = ok and cond

    chk(info_reads_per == 2, f"job_info 读 = 2/触发（selectById + FOR UPDATE），实际 {info_reads_per:.2f}（调度扫描 {scan_noise} 条已剔除）")
    chk(info_writes_per == 1, f"job_info 写 = 1/触发（ack touch_last_time 窄更新），实际 {info_writes_per:.2f}")
    chk(log_inserts == K, f"job_log INSERT = 1/触发（带 executor_address 的 INSERT），实际 {log_inserts}")
    chk(log_writes == K, f"job_log UPDATE = 1/触发（回调条件窄更新 IN(0,3)），实际 {log_writes}")
    chk(len(full_log_addr_update) == 0, f"无「补写地址」的全行 UPDATE job_log（P1 去 #10 收敛点），实际 {len(full_log_addr_update)}")
    chk(len(full_info_update) == 0, f"无整行 UPDATE job_info（除 touch_last_time），实际 {len(full_info_update)}")
    chk(4.5 <= core_per_trigger <= 6.0, f"job_info+job_log 每触发 {core_per_trigger:.2f} ∈ [4.5,6.0]（契约 ~5-6）")
    # 会话内若夹带 executor 心跳会有 registry 噪音：仅提示不判错（心跳走 job_registry，会 +N 读）
    print(f"\n注：job_registry 相关 {reg} 条含 route 读(~1/触发) + 可能夹带的 executor 心跳，不纳入核心断言", flush=True)

    if ok:
        print("\n== 写放大契约 PASS：收敛后单次手动触发 DB 往返 ≈5-6 条（含 1 INSERT + 2 窄 UPDATE + 2 读）==\n", flush=True)
    else:
        print("\n== 写放大契约有 FAIL，见上 ==\n", flush=True)
        sys.exit(1)
finally:
    try:
        q("SET global general_log=OFF", db="mysql")
    except Exception:
        pass
    if jid:
        try:
            requests.post(f"{BASE}/job/{jid}/stop", headers=h, timeout=10)
            requests.delete(f"{BASE}/job/{jid}", headers=h, timeout=10)
            q("DELETE FROM job_log WHERE job_id=%s", (jid,))
            print(f"cleaned scratch job {jid} + logs, general_log OFF", flush=True)
        except Exception as e:
            print(f"cleanup {jid} failed: {e}", flush=True)
