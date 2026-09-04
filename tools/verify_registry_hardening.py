#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
注册中心加固回归（spec 验收清单，单 admin dev 环境）：V1 心跳幂等 / V2 路由新鲜度 / V3 offline 幂等+自愈。
V4 优雅停机为交互步骤（Ctrl+C executor），另做。

前置：admin 8080(local, 3306 ww_job) + executor samples 8081 已启动，executor 以
  {registryKey:sample-executor, registryValue:127.0.0.1:8081} 注册到 group_id=1。
DB 凭据复用 registry_unique_key_migration（读 application-local.yml，不打印）。
用法：python tools/verify_registry_hardening.py
"""
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import pymysql
import requests

sys.path.insert(0, __file__ and __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

BASE = "http://localhost:8080"
GROUP_ID = 1
KEY = "sample-executor"
VALUE = "127.0.0.1:8081"
JOB_ID = 23  # test3 demoHandler round_robin serial, disabled（手动触发不受启停限制）


def db():
    return pymysql.connect(host="localhost", port=3306, user="root",
                           password=mig.read_local_password(), database="ww_job",
                           charset="utf8mb4", autocommit=True)


def q(sql, args=None):
    conn = db()
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.fetchall()
    finally:
        conn.close()


def login():
    r = requests.post(f"{BASE}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def reg(body, path="/registry"):
    return requests.post(f"{BASE}{path}", json=body, timeout=10)


def count_row(value=VALUE):
    return q("SELECT COUNT(*) FROM job_registry WHERE job_group_id=%s AND registry_value=%s",
             (GROUP_ID, value))[0][0]


def trigger(job_id, headers):
    return requests.post(f"{BASE}/job/{job_id}/trigger", headers=headers, timeout=30)


def latest_log(job_id):
    rows = q("SELECT id, status, handle_msg FROM job_log WHERE job_id=%s ORDER BY id DESC LIMIT 1", (job_id,))
    return rows[0] if rows else None


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg)
    if not ok:
        sys.exit(1)


def main():
    hdrs = login()
    print("login ok")

    # 基线：executor 心跳已在 → 该 value 恰 1 行
    check(count_row() == 1, f"基线：{VALUE} 行数=1（实际 {count_row()}）")

    # ---- V1 心跳幂等：同 value 连续 2 次 /registry 后仍 1 行 ----
    for _ in range(2):
        r = reg({"registryKey": KEY, "registryValue": VALUE})
        check(r.status_code == 200, f"重复 register 200（code={r.json().get('code')}）")
    check(count_row() == 1, f"V1a 连续 2 次 upsert → 1 行（实际 {count_row()}）")

    # ---- V1b 并发：同 value 8 线程同时 upsert（含插入新值路径 + 刷新既有值路径）----
    def hammer(v):
        return reg({"registryKey": KEY, "registryValue": v}).status_code
    fake = "127.0.0.1:5555"
    with ThreadPoolExecutor(max_workers=8) as ex:
        real_codes = list(ex.map(hammer, [VALUE] * 8))
        fake_codes = list(ex.map(hammer, [fake] * 8))
    check(all(c == 200 for c in real_codes + fake_codes), "并发 upsert 全部 200")
    check(count_row() == 1, f"V1b 既有值并发刷新 → 1 行（实际 {count_row()}）")
    check(count_row(fake) == 1, f"V1b 新值并发插入 → 1 行（实际 {count_row(fake)}）")
    reg({"registryKey": KEY, "registryValue": fake}, path="/registry/offline")
    check(count_row(fake) == 0, "清理 fake value 行")

    # ---- V2 路由按 B2 语义（2026-09-04 Stage B 起）----
    # route 在线性以「本 admin 内存缓存」为权威，缓存写穿自同一 admin 的 upsert(/registry /heartbeat)、
    # offline(删行)、cleaner(90s 阈值)。生产路径下 DB 心跳行只经这些 admin 写点变更，与缓存同写同删，
    # 不会出现「DB 陈旧但缓存新鲜」。旧版「直接 SQL 调旧 DB 心跳 → 期望不入 route」的手法因此失效
    # （SQL 绕过写点，缓存仍新鲜 → 照常路由，实测 status=1 —— 恰证 B2 缓存生效）。
    # 新鲜度/驱逐不变式改经同生产通道验证：offline 驱逐、B-2 空集回退、回退仍按 90s 过滤僵尸。
    #
    # V2a: B-2 空集回退 —— offline（DB 删行 + 缓存剔除）→ 立即触发 → 缓存空→回退 DB(空)→「无可用执行器」
    r = reg({"registryKey": KEY, "registryValue": VALUE}, path="/registry/offline")
    check(r.status_code == 200 and r.json().get("code") == 200, "V2a offline 200（清 DB 行 + 缓存）")
    check(count_row() == 0, f"V2a offline 后 DB 无行（实际 {count_row()}）")
    tr = trigger(JOB_ID, hdrs)
    check(tr.status_code == 200, f"V2a 缓存空手动 trigger 200（http={tr.status_code}）")
    log = latest_log(JOB_ID)
    print(f"      V2a 最新日志 id={log[0]} status={log[1]} msg={log[2]}")
    check(log[1] == 2 and log[2] is not None and "无可用执行器" in log[2],
          f"V2a 空集回退 DB → status=2 无可用执行器（实际 status={log[1]}）")

    # V2b: 回退仍过滤僵尸 —— 缓存空态直插一条 >90s 陈旧心跳行（独立 value，避开与真实行唯一键冲突；
    #      绕过 admin 写点，模拟 DB 残留旧行）。触发经 DB 回退的 90s 阈值过滤 → 仍「无可用执行器」，
    #      新鲜度口径不因 B-2 回退而失守。
    zombie = "127.0.0.1:9999"
    q("INSERT INTO job_registry (job_group_id, registry_key, registry_value, heartbeat_time) "
      "VALUES (%s, %s, %s, DATE_SUB(NOW(), INTERVAL 100 SECOND))", (GROUP_ID, KEY, zombie))
    check(count_row(zombie) == 1, "V2b 僵尸行已直插 DB（行数=1）")
    tr = trigger(JOB_ID, hdrs)
    check(tr.status_code == 200, f"V2b 缓存空+僵尸行手动 trigger 200（http={tr.status_code}）")
    log = latest_log(JOB_ID)
    print(f"      V2b 最新日志 id={log[0]} status={log[1]} msg={log[2]}")
    check(log[1] == 2 and log[2] is not None and "无可用执行器" in log[2],
          f"V2b 回退按 90s 过滤僵尸 → status=2 无可用执行器（实际 status={log[1]}）")
    q("DELETE FROM job_registry WHERE job_group_id=%s AND registry_value=%s", (GROUP_ID, zombie))
    check(count_row(zombie) == 0, "V2b 僵尸行已清理")

    # V2c: 恢复在线 —— register(upsert：DB+缓存同写) → 路由恢复，派给 executor 落 status=1
    r = reg({"registryKey": KEY, "registryValue": VALUE})
    check(r.status_code == 200, "V2c 恢复 register 200")
    check(count_row() == 1, f"V2c 恢复后行数=1（实际 {count_row()}）")
    tr = trigger(JOB_ID, hdrs)
    check(tr.status_code == 200, "V2c 恢复后手动 trigger 200")
    time.sleep(1.0)
    log = latest_log(JOB_ID)
    print(f"      V2c 最新日志 id={log[0]} status={log[1]} msg={log[2]}")
    check(log[1] == 1, f"V2c 恢复在线 → 派给 executor 成功落 status=1（实际 status={log[1]}）")

    # ---- V3 offline 幂等 + 自愈 ----
    r = reg({"registryKey": KEY, "registryValue": VALUE}, path="/registry/offline")
    check(r.status_code == 200 and r.json().get("code") == 200, "offline 200")
    check(count_row() == 0, f"V3a offline 后该行删除（行数={count_row()}）")
    r = reg({"registryKey": KEY, "registryValue": VALUE}, path="/registry/offline")
    check(r.status_code == 200 and r.json().get("code") == 200, "重复 offline 仍 200（幂等）")
    # 自愈：等下一轮心跳（≤30s+margin）行加回
    seen = None
    for _ in range(40):
        c = count_row()
        if c == 1:
            seen = c
            break
        time.sleep(1)
    check(seen == 1, f"V3b 自愈：≤30s 心跳后行自动加回（轮询结果 seen={seen}）")

    print("\nV1/V2/V3 全绿。V4 优雅停机单独交互验证。")


if __name__ == "__main__":
    main()
