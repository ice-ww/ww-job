#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B1 合并单事务语义回归：blocked fire 也消费 boundary（B-1）+ SINGLE 互斥不被合并破坏。

背景：decideCron 把 claim（status/claimable/advance）与 decide（SINGLE gate/route/INSERT）合并进
单个 @Transactional（Stage A 是两个独立事务）。B-1 次序 = advance 先于 SINGLE gate → blocked fire
（上一次执行未结束）仍推进 trigger_next_time、落一条 STATUS_BLOCKED=4 日志、不把边界留在原地。
若合并破坏该语义（advance 未随 blocked 提交 / 丢边界），表现为：同一边界被重复尝试、或 next_time
卡在 blocked 边界不再推进、或 SINGLE 互斥失效出现并发执行。

场景：SINGLE + slowHandler(15s) + 0/5 cron，routeStrategy=round_robin（非 sharding → 走 triggerCronFast
= decideCron 合并路径）。15s 运行期横跨 3 个 */5 边界 → 每轮运行产生 ~2-3 次 blocked fire，
随后恢复为成功运行。观察 ~50s，断言：
  (a) ≥1 条 status=4 blocked 日志（handle_msg 含「被阻塞丢弃」）——SINGLE 门在合并事务内生效；
  (b) 相邻 accepted(0/1) 火种触发秒差 ≥14s——同一时刻至多一个运行，无并发执行；
  (c) 全窗各行 trigger 秒级两两不同（每个边界恰一行，无同边界重复尝试 = blocked 已消费边界）；
  (d) 窗口尾部 trigger_next_time 两次采样 ~6s 间隔严格递增——推进未卡在 blocked 边界。

清理：finally 停+删 scratch job + 删其 job_log。凭据复用 registry_unique_key_migration（不打印）。
"""
import sys
import time

import pymysql
import requests

sys.path.insert(0, __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

BASE = "http://localhost:8080"
DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          database="ww_job", charset="utf8mb4", autocommit=True)
JOBNAME = "p1-b1-blocked"


def db():
    return pymysql.connect(**DB)


def q(sql, args=None):
    with db() as c:
        with c.cursor() as cur:
            cur.execute(sql, args) if args is not None else cur.execute(sql)
            return cur.fetchall()


def q1(sql, args=None):
    rows = q(sql, args)
    return rows[0] if rows else None


def login():
    r = requests.post(f"{BASE}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=10)
    assert r.json().get("code") == 200, f"登录失败: {r.json()}"
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def api(headers, method, path, body=None):
    r = requests.request(method, f"{BASE}{path}", headers=headers, json=body, timeout=15)
    return r.json()


def create_job(headers, **kw):
    base = dict(jobName=JOBNAME, jobGroupId=1, jobDesc="p1-b1 blocked-boundary regression scratch",
                handlerName="slowHandler", executorParam="", cron="0/5 * * * * ?",
                routeStrategy="round_robin", blockStrategy="SINGLE", retryCount=0, timeout=0, triggerStatus=0)
    base.update(kw)
    return api(headers, "POST", "/job", base)["data"]


def delete_job(headers, jid):
    api(headers, "DELETE", f"/job/{jid}")


def delete_logs(jid):
    q("DELETE FROM job_log WHERE job_id=%s", (jid,))


def rows(jid, after_id=0):
    """按 id 序返回 (id, trigger_epoch_s, status, handle_msg 前 30 字)"""
    r = q("SELECT id, UNIX_TIMESTAMP(trigger_time), status, LEFT(COALESCE(handle_msg,''),30) "
          "FROM job_log WHERE job_id=%s AND id>%s ORDER BY id", (jid, after_id))
    return r


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg, flush=True)
    return ok


def main():
    ok = True
    headers = login()
    jid = create_job(headers)
    print(f"created scratch job {jid}: SINGLE slowHandler 0/5 cron, route=round_robin (merged decideCron path)", flush=True)
    try:
        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s", (jid,))[0]
        print(f"enable 前 job_log max_id={before}", flush=True)
        r = api(headers, "POST", f"/job/{jid}/start")
        assert r.get("code") == 200, f"start 失败: {r}"
        print("job 已启用，观察 ~55s（首轮 15s 慢执行 + 后续 blocked 恢复）...", flush=True)

        # 采集窗：累计 ~55s，期望看到 ≥2 条 blocked 与后续恢复的成功行。
        # 关键：job_log 行是「每边界一次 fire 落一条」；RUNNING(0) 行被回调原地翻成 success(1) 不是新 fire。
        # 故按 id 去重（首见分类）：id 首次出现 status==4 ⇒ blocked（终态插入，永不翻转）；
        # 否则 accepted（0 起跑，随后异步 0→1）。poll 到的同 id 状态翻转只更新打印，不新增事件。
        events = []          # [(id, trigger_sec, status)] 按 fire 事件序，每 id 一条
        seen_ids = set()
        seen_blocked = 0
        seen_recover = False
        start = time.time()
        while time.time() - start < 55:
            for rr in rows(jid, before):
                lid, tsec, st = rr[0], rr[1], rr[2]
                if lid not in seen_ids:
                    seen_ids.add(lid)
                    events.append((lid, tsec, st))
                    mark = {0: "RUNNING→success", 4: "BLOCKED"}.get(st, f"st{st}")
                    print(f"  +fire id={lid} t={tsec}s status={st}({mark}) msg={rr[3]!r}", flush=True)
                    if st == 4:
                        seen_blocked += 1
                    elif seen_blocked > 0:
                        seen_recover = True
            # 已见 ≥2 blocked 且其后出现 accepted（恢复）→ 提前收敛
            if seen_blocked >= 2 and seen_recover:
                print("  已见 ≥2 blocked + 恢复 accepted，提前收敛", flush=True)
                break
            time.sleep(2)

        if not events:
            print("窗口内 0 个 fire——任务未触发，检查执行器/调度", file=sys.stderr)
            ok = False
        else:
            # (a) ≥1 blocked
            ok &= check(seen_blocked >= 1, f"出现 ≥1 条 status=4 blocked（实际 {seen_blocked}）——SINGLE 门生效")
            # (b) accepted 运行触发秒差 ≥14s → 同一时刻至多一个运行（无并发执行）
            acc = [e for e in events if e[2] != 4]
            gaps = [acc[i][1] - acc[i - 1][1] for i in range(1, len(acc))]
            if len(acc) >= 2:
                ok &= check(min(gaps) >= 14, f"相邻 accepted 触发间隔 ≥14s（实际 min {min(gaps):.1f}s，{len(acc)} 次运行）——无并发")
            else:
                ok &= check(len(acc) >= 2, f"至少 2 次 accepted 运行以便检验互斥（实际 {len(acc)}）")
            # (c) 各行 trigger 秒级两两不同（blocked 消费边界：无同边界重复尝试）
            secs = [e[1] for e in events]
            dup = len(secs) - len(set(secs))
            ok &= check(dup == 0, f"全窗 {len(secs)} 个 fire 触发秒两两不同（同边界重复尝试 {dup} 次 = B-1 破坏）")
            # (d) 窗口尾部 next_time 两次采样严格递增（推进未卡在 blocked 边界）
            nt1 = q1("SELECT trigger_next_time FROM job_info WHERE id=%s", (jid,))[0]
            time.sleep(6)
            nt2 = q1("SELECT trigger_next_time FROM job_info WHERE id=%s", (jid,))[0]
            ok &= check(nt2 is not None and nt1 is not None and nt2 > nt1,
                        f"next_time 6s 内推进 {nt1} → {nt2}（严格递增 = blocked 边界已消费、推进未停滞）")

        print(f"\n== B1 blocked-boundary + SINGLE 互斥回归 {'PASS' if ok else 'FAIL'} ==", flush=True)
        if not ok:
            sys.exit(1)
    finally:
        try:
            api(headers, "POST", f"/job/{jid}/stop")
        except Exception:
            pass
        try:
            delete_job(headers, jid)
            delete_logs(jid)
            print(f"cleaned scratch job {jid} + logs", flush=True)
        except Exception as e:
            print(f"清理 {jid} 失败: {e}", flush=True)


if __name__ == "__main__":
    main()
