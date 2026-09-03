#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
item2 超时巡检时区去耦回归：
  A. 时区去耦决定性证明：旧 SQL(含 NOW())结果跨会话时区翻转；新 SQL(应用侧字面参数)跨时区不变。
  B. 语义不回归：合成 status=0 / trigger_time=now-150s 的 running 行 → 一个扫描周期(≤~65s)内被翻成 status=3。

前置：admin 8080(local, 3306 ww_job) 已用新代码启动（B 需要其扫描器在跑；A 纯 SQL 不依赖 admin）。
DB 凭据复用 registry_unique_key_migration（读 application-local.yml，不打印）。
用法：python tools/verify_timeout_boundary.py
"""
import sys
import time
from datetime import datetime, timedelta

import pymysql

sys.path.insert(0, __file__ and __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          database="ww_job", charset="utf8mb4", autocommit=True)


def db():
    return pymysql.connect(**DB)


def q(sql, args=None, tz=None):
    conn = db()
    try:
        with conn.cursor() as cur:
            if tz:
                cur.execute("SET time_zone = %s", (tz,))
            cur.execute(sql, args or ())
            return cur.fetchall()
    finally:
        conn.close()


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg)
    if not ok:
        sys.exit(1)


def main():
    # ---- A. 时区去耦决定性证明（只读，不写业务行）----
    # C08 = 「应用侧时钟」标签：+08 会话的 NOW()，等价于应用 LocalDateTime.now() 落库值
    C08 = q("SELECT NOW()", tz="+08:00")[0][0]
    C08s = C08.strftime("%Y-%m-%d %H:%M:%S")
    TT = (C08 - timedelta(seconds=150)).strftime("%Y-%m-%d %H:%M:%S")  # 应用已写 trigger_time：150s 前
    print(f"      C08(应用时钟)={C08s}  存量行 trigger_time={TT}（150s 前，60s 超时 → 应判超时）")

    def old_pred(tz):
        # 修复前谓词：DB 时钟 NOW() - 60s
        return q("SELECT %s < NOW() - INTERVAL 60 SECOND", (TT,), tz=tz)[0][0]

    def new_pred(tz):
        # 修复后谓词：应用侧字面 now - 60s
        return q("SELECT %s < %s - INTERVAL 60 SECOND", (TT, C08s), tz=tz)[0][0]

    o8, o0 = old_pred("+08:00"), old_pred("+00:00")
    n8, n0 = new_pred("+08:00"), new_pred("+00:00")
    print(f"      旧谓词(NOW) : +08={o8}  +00={o0}     新谓词(参数) : +08={n8}  +00={n0}")
    check(o8 == 1 and o0 == 0,
          f"A1 旧 SQL 结果随时区翻转（+08 判超时 → +00 不判，~8h 错位成立）")
    check(n8 == 1 and n0 == 1,
          f"A2 新 SQL 结果跨时区稳定（+08/+00 均判超时，去耦成立）")

    # ---- B. 语义不回归：合成陈旧 running 行 → 扫描翻 status=3 ----
    job = q("""SELECT id, job_group_id, handler_name FROM job_info
               WHERE trigger_status = 0 AND (alarm_config IS NULL OR alarm_config = '')
               ORDER BY id LIMIT 1""")
    check(bool(job), "B1 找到停用且未订阅告警的 job 作载体")
    jid, jgid, hname = job[0]
    tt = datetime.now() - timedelta(seconds=150)
    conn = db()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO job_log (job_id, job_group_id, executor_address, handler_name,
                     trigger_type, trigger_time, handle_msg, status, shard_index)
                   VALUES (%s,%s,'127.0.0.1:9999',%s,'manual',%s,'item2-synthetic',0,0)""",
                (jid, jgid, hname, tt))
            row_id = cur.lastrowid
    finally:
        conn.close()
    print(f"      合成 running 行 id={row_id} job_id={jid} trigger_time={tt}（等扫描 ≤~65s）")
    st = msg = None
    for _ in range(100):
        r = q("SELECT status, handle_msg FROM job_log WHERE id=%s", (row_id,))
        if r and r[0][0] != 0:
            st, msg = r[0]
            break
        time.sleep(1)
    check(st == 3, f"B2 陈旧 running 行被翻 status=3（实际 status={st}）")
    check(msg is not None and "执行超时未收到回调" in msg,
          f"B3 handle_msg 正确（{msg!r}）")
    q("DELETE FROM job_log WHERE id=%s", (row_id,))
    print(f"      清理合成行 id={row_id}")

    print("\nA 时区去耦 + B 语义回归 全绿。")


if __name__ == "__main__":
    main()
