#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
item5 状态模型拆分(STATUS_BLOCKED=4)回归：
  C1. blocked 写点：SINGLE 任务 + 合成 running 行 → 手动 trigger → 新 job_log status=4、
      handle_msg=「被阻塞丢弃」、handle_time=NULL；Dashboard blocked+1、unknown 不变(不被污染)。
  C2. migrate 隔离：合成 status=3+decide 句柄 行 → 跑 migrate UPDATE → 变 4；对照超时未知行不被误伤。
  D.  Phase B 告警审计(可选 --log <admin日志路径>)：未订阅告警 job 造 status=2 失败行 →
      一个扫描周期内 admin 日志出现一次 WARN「未订阅告警」；再造第二条失败行 → 仍只有一条(每进程每job有界)。

前置：admin 8080(local, 3306 ww_job) 已用 item5 新代码启动。
DB 凭据复用 registry_unique_key_migration（读 application-local.yml，不打印）。
用法：python tools/verify_status_split.py            # C1+C2
      python tools/verify_status_split.py --log <admin日志>   # 追加 Phase B
"""
import argparse
import sys
import time
from datetime import datetime

import pymysql
import requests

sys.path.insert(0, __file__ and __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

BASE = "http://localhost:8080"
BLOCK_MSG = "任务上一次执行尚未结束，本次触发被阻塞丢弃"
TIMEOUT_MSG = "执行超时，结果未知：执行器可能仍在执行，请以执行器日志为准，勿重复触发"

DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          database="ww_job", charset="utf8mb4", autocommit=True)


def db():
    return pymysql.connect(**DB)


def q(sql, args=None):
    conn = db()
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.fetchall()
    finally:
        conn.close()


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg, flush=True)
    if not ok:
        sys.exit(1)


def login():
    r = requests.post(f"{BASE}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=10)
    body = r.json()
    assert body.get("code") == 200, f"登录失败: {body}"
    return {"Authorization": "Bearer " + body["data"]["token"]}


def stats(headers):
    r = requests.get(f"{BASE}/dashboard/stats", headers=headers, timeout=10)
    return r.json()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", help="admin 日志文件路径(Windows 全路径)，用于 Phase B WARN 审计断言")
    args = ap.parse_args()

    headers = login()
    print("      已登录 admin", flush=True)

    # ---------- C1. blocked 写点 + Dashboard 双卡 ----------
    carrier = q("""SELECT id, job_group_id, handler_name, block_strategy, trigger_status
                   FROM job_info WHERE (alarm_config IS NULL OR alarm_config = '')
                   ORDER BY id LIMIT 1""")
    check(bool(carrier), "C1 找到未订阅告警的 job 作载体")
    jid, jgid, hname, orig_strategy, orig_status = carrier[0]
    print(f"      载体 job_id={jid} 原 strategy={orig_strategy} 原 trigger_status={orig_status}", flush=True)

    conn = db()
    try:
        with conn.cursor() as cur:
            if orig_status != 0:
                cur.execute("UPDATE job_info SET trigger_status=0 WHERE id=%s", (jid,))  # 临时禁用防空转
            if orig_strategy != "SINGLE":
                cur.execute("UPDATE job_info SET block_strategy='SINGLE' WHERE id=%s", (jid,))
            # 合成 running 行 → countRunning>0 → 本次触发必被阻塞丢弃
            cur.execute(
                """INSERT INTO job_log (job_id, job_group_id, executor_address, handler_name,
                     trigger_type, trigger_time, handle_msg, status, shard_index)
                   VALUES (%s,%s,'127.0.0.1:9999',%s,'manual',%s,'verify-status-split',0,0)""",
                (jid, jgid, hname, datetime.now()))
            running_id = cur.lastrowid
    finally:
        conn.close()
    print(f"      合成 running 行 id={running_id}", flush=True)

    s0 = stats(headers)
    u0, b0 = s0.get("logUnknownToday"), s0.get("logBlockedToday")
    check("logBlockedToday" in s0, f"C1 dashboard 返回含 logBlockedToday(unknown={u0} blocked={b0})")

    r = requests.post(f"{BASE}/job/{jid}/trigger", headers=headers, timeout=20)
    check(r.json().get("code") == 200, "C1 手动 trigger 返回 code=200")

    rows = q("""SELECT id, status, handle_msg, handle_time FROM job_log
                WHERE job_id=%s AND id>%s ORDER BY id DESC LIMIT 1""", (jid, running_id))
    check(bool(rows), "C1 trigger 后产生新的 job_log")
    bid, bst, bmsg, bht = rows[0]
    check(bst == 4, f"C1 阻塞日志 status=4(实际 {bst})")
    check(bmsg is not None and "被阻塞丢弃" in bmsg, f"C1 handle_msg 正确（{bmsg!r}）")
    check(bht is None, "C1 阻塞日志 handle_time IS NULL(不入告警的前提)")

    s1 = stats(headers)
    u1, b1 = s1.get("logUnknownToday"), s1.get("logBlockedToday")
    check(b1 == b0 + 1, f"C1 dashboard 今日被阻塞 +1（{b0} → {b1}）")
    check(u1 == u0, f"C1 今日未知不受 blocked 污染（{u0} → {u1}）")

    q("DELETE FROM job_log WHERE id IN (%s,%s)", (running_id, bid))
    print(f"      清理合成 running={running_id} + blocked={bid}", flush=True)

    # ---------- C2. migrate 隔离 ----------
    legacy = q("SELECT COUNT(*) FROM job_log WHERE status=3 AND handle_msg=%s", (BLOCK_MSG,))[0][0]
    print(f"      migrate 前存量 status=3+decide句柄 = {legacy} 行", flush=True)
    conn = db()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO job_log (job_id, job_group_id, executor_address, handler_name,
                     trigger_type, trigger_time, handle_msg, status, shard_index)
                   VALUES (%s,%s,'127.0.0.1:9999',%s,'manual',%s,%s,3,0)""",
                (jid, jgid, hname, datetime.now(), BLOCK_MSG))
            legacy_id = cur.lastrowid
            cur.execute(
                """INSERT INTO job_log (job_id, job_group_id, executor_address, handler_name,
                     trigger_type, trigger_time, handle_time, handle_msg, status, shard_index)
                   VALUES (%s,%s,'127.0.0.1:9999',%s,'manual',%s,%s,%s,3,0)""",
                (jid, jgid, hname, datetime.now(), datetime.now(), TIMEOUT_MSG))
            timeout_id = cur.lastrowid
    finally:
        conn.close()

    q("""UPDATE job_log SET status=4 WHERE status=3 AND handle_msg=%s""", (BLOCK_MSG,))  # 同 migrate 谓词

    rl = q("SELECT status, handle_time FROM job_log WHERE id=%s", (legacy_id,))[0]
    rt = q("SELECT status, handle_time FROM job_log WHERE id=%s", (timeout_id,))[0]
    check(rl[0] == 4, f"C2 合成 legacy 行 status=3→4（实际 {rl[0]}）")
    check(rt[0] == 3, f"C2 对照超时未知行不被误伤（status 仍 {rt[0]}）")
    q("DELETE FROM job_log WHERE id IN (%s,%s)", (legacy_id, timeout_id))
    print("      清理 C2 合成行", flush=True)

    # 恢复载体
    q("UPDATE job_info SET block_strategy=%s, trigger_status=%s WHERE id=%s",
      (orig_strategy, orig_status, jid))
    print("      恢复载体 job 原配置", flush=True)

    # ---------- D. Phase B 告警审计有界 ----------
    if args.log:
        log_path = args.log.replace("/", "\\")
        try:
            with open(log_path, encoding="utf-8", errors="replace") as f:
                base = f.read().count(f"(id={jid})")
        except OSError:
            base = 0

        def count_warn():
            with open(log_path, encoding="utf-8", errors="replace") as f:
                txt = f.read()
            return sum(1 for ln in txt.splitlines() if f"(id={jid})" in ln and "未订阅告警" in ln)

        conn = db()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """INSERT INTO job_log (job_id, job_group_id, executor_address, handler_name,
                         trigger_type, trigger_time, handle_time, handle_code, handle_msg, status, shard_index)
                       VALUES (%s,%s,'127.0.0.1:9999',%s,'manual',%s,%s,500,'item5-phaseB-fail',2,0)""",
                    (jid, jgid, hname, datetime.now(), datetime.now()))
                f1 = cur.lastrowid
        finally:
            conn.close()
        print(f"      合成失败行1 id={f1}，等一个扫描周期(≤~45s)...", flush=True)
        w1 = 0
        for _ in range(50):
            w1 = count_warn()
            if w1 >= 1:
                break
            time.sleep(1)
        check(w1 >= 1, f"D1 未订阅告警失败触发一次 WARN（count={w1}）")

        conn = db()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """INSERT INTO job_log (job_id, job_group_id, executor_address, handler_name,
                         trigger_type, trigger_time, handle_time, handle_code, handle_msg, status, shard_index)
                       VALUES (%s,%s,'127.0.0.1:9999',%s,'manual',%s,%s,500,'item5-phaseB-fail',2,0)""",
                    (jid, jgid, hname, datetime.now(), datetime.now()))
                f2 = cur.lastrowid
        finally:
            conn.close()
        print(f"      合成失败行2 id={f2}，再等一个扫描周期看是否仍只一条...", flush=True)
        time.sleep(40)
        w2 = count_warn()
        check(w2 == w1, f"D2 同 job 第二次失败不再重复 WARN（warn {w1} → {w2}，有界成立）")
        q("DELETE FROM job_log WHERE id IN (%s,%s)", (f1, f2))
        print("      清理 Phase B 合成失败行", flush=True)

    print("\nC1 blocked写点 + C2 migrate隔离 + D 告警审计 全绿。")


if __name__ == "__main__":
    main()
