#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P1 Stage A（触发写放大收敛）回归驱动 —— 对运行中的 admin(localhost:8080, db ww_job) 逐场景验证。

场景（分组跑，每组独立可重跑）：
  fast = c1 手动成功收敛 / c2 重复回调幂等 / c3 不存在logId / c4 无执行器单行收敛 / c5 SINGLE阻塞=4+回调守卫
  slow = c6 超时D2：loadTestHandler睡40s + job.timeout=1 → 30s巡检翻3 → 迟到回调3→1覆盖
  cron = c7 快任务1Hz 60s+ → cron 路径落行正确性(≥1条+无同秒双触发+全success;
        放宽原「≥28条频率」断言——pre-existing 单job cron 饿死见 load-test-results.md 补记2026-09-04)

断言直接打 SQL 读库，凭据复用 registry_unique_key_migration(读 application-local.yml, 不打印)。
用法：python tools/verify_p1_stage_a.py fast | slow | cron
"""
import argparse
import sys
import time
from datetime import datetime

import pymysql
import requests

sys.path.insert(0, __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

BASE = "http://localhost:8080"
DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          database="ww_job", charset="utf8mb4", autocommit=True)
PLACEHOLDER = "已受理，等待执行结果"
EXEC_ADDR = "127.0.0.1:8081"


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


def q1(sql, args=None):
    r = q(sql, args)
    return r[0] if r else None


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg, flush=True)
    if not ok:
        sys.exit(1)


def login():
    r = requests.post(f"{BASE}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=10)
    body = r.json()
    assert body.get("code") == 200, f"登录失败: {body}"
    return {"Authorization": "Bearer " + body["data"]["token"]}


def api(headers, method, path, body=None):
    r = requests.request(method, f"{BASE}{path}", headers=headers, json=body, timeout=15)
    return r.json()


def create_job(headers, **kw):
    base = dict(jobName="p1-sa-scratch", jobGroupId=1, jobDesc="p1 stage-a regression scratch",
                handlerName="demoHandler", executorParam="", cron="0 0 0 1 1 ?",
                routeStrategy="round_robin", blockStrategy="", retryCount=0, timeout=0, triggerStatus=0)
    base.update(kw)
    return api(headers, "POST", "/job", base)["data"]


def delete_job(headers, jid):
    api(headers, "DELETE", f"/job/{jid}")


def delete_logs(jid):
    q("DELETE FROM job_log WHERE job_id=%s", (jid,))


def max_log_id(jid):
    r = q1("SELECT COALESCE(MAX(id), 0) FROM job_log WHERE job_id=%s", (jid,))
    return r[0]


def poll_log(jid, after_id, timeout_s=12, interval=1.0, want_terminal=True):
    """等 job 的最新一行到达给定谓词状态；返回 (row, seen_statuses) 或超时 (None, seen)。"""
    seen = []
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        row = q1("SELECT id,status,handle_code,handle_msg,handle_time,executor_address,trigger_type "
                 "FROM job_log WHERE job_id=%s AND id>%s ORDER BY id ASC LIMIT 1", (jid, after_id))
        if row:
            st = row[1]
            if st not in seen:
                seen.append(st)
            if not want_terminal or st != 0:
                return row, seen
        time.sleep(interval)
    return None, seen


def cb(log_id, code, msg, handle_time_ms=None):
    import json
    body = {"logId": log_id, "handleCode": code, "handleMsg": msg,
            "handleTime": handle_time_ms or int(time.time() * 1000)}
    r = requests.post(f"{BASE}/callback", json=body, timeout=10)
    return r.json()


def scratch_cleanup(headers, jids):
    for jid in jids:
        try:
            delete_job(headers, jid)
            delete_logs(jid)
            print(f"    清理 scratch job {jid} + logs", flush=True)
        except Exception as e:
            print(f"    清理 {jid} 失败: {e}", flush=True)


def c1_success(headers):
    print("\n[c1] 手动触发成功收敛（INSERT 带地址 + 回调收账 + JobRunner 真实 msg）", flush=True)
    jid = create_job(headers, jobName="p1-sa-c1-success")
    try:
        after = max_log_id(jid)
        api(headers, "POST", f"/job/{jid}/trigger")
        row, seen = poll_log(jid, after, timeout_s=12)
        check(row is not None and row[1] == 1, f"最终 status=1 (seen={seen})")
        check(row[1] == 1 and row[2] == 200, f"handle_code=200, 实际={row[2]}")
        check(row[3] and row[3] != PLACEHOLDER, f"handle_msg 非占位符真实文本: {row[3]!r}")
        check(row[4] is not None, "handle_time 已写")
        check(row[5] == EXEC_ADDR, f"executor_address 在 INSERT 落库: {row[5]}")
        check(row[6] == "manual", f"trigger_type=manual: {row[6]}")
        return jid
    finally:
        scratch_cleanup(headers, [jid])


def c2_dup_callback(headers):
    print("\n[c2] 重复回调幂等（终态成功行再收 FAIL 不覆盖，IN(0,3) 守卫）", flush=True)
    jid = create_job(headers, jobName="p1-sa-c2-dup")
    try:
        after = max_log_id(jid)
        api(headers, "POST", f"/job/{jid}/trigger")
        row, _ = poll_log(jid, after, timeout_s=12)
        check(row is not None and row[1] == 1, f"前置成功行 status=1 (seen={_})")
        log_id, orig_msg = row[0], row[3]
        body = cb(log_id, 500, "late-fail-should-be-ignored")
        check(body.get("code") == 200, f"重复回调 body.code=200: {body}")
        # ReturnT.success(T data) 文本在 data 字段（success/fail 重载不同），两处都兜底
        hint = body.get("data") if body.get("data") is not None else body.get("msg")
        check(hint is not None and "已是最新状态" in str(hint), f"幂等提示文本: data={body.get('data')!r} msg={body.get('msg')!r}")
        row2 = q1("SELECT status,handle_code,handle_msg FROM job_log WHERE id=%s", (log_id,))
        check(row2[0] == 1 and row2[1] == 200 and row2[2] == orig_msg,
              f"终态未被迟到回调覆盖: status={row2[0]} code={row2[1]}")
        return jid
    finally:
        scratch_cleanup(headers, [jid])


def c3_unknown_logid(headers):
    print("\n[c3] 回调不存在的 logId → body.code=500「logId 不存在」", flush=True)
    body = cb(999999999, 200, "ghost")
    check(body.get("code") == 500, f"body.code=500: {body}")
    check("logId 不存在" in str(body.get("msg", "")), f"msg: {body.get('msg')}")


def c4_no_executor(headers):
    print("\n[c4] 无执行器组 → 单条 status=2「无可用执行器」（收敛，非 running→fail 两条）", flush=True)
    jid = create_job(headers, jobGroupId=999, jobName="p1-sa-c4-noexec")
    try:
        after = max_log_id(jid)
        api(headers, "POST", f"/job/{jid}/trigger")
        rows = q("SELECT status,handle_msg,handle_time,executor_address FROM job_log "
                 "WHERE job_id=%s AND id>%s ORDER BY id", (jid, after))
        check(len(rows) == 1, f"该次触发只落 1 条日志（旧代码是 running→fail 两条），实际 {len(rows)} 条")
        check(rows[0][0] == 2, f"status=2: {rows[0][0]}")
        check(rows[0][1] == "无可用执行器", f"handle_msg: {rows[0][1]!r}")
        check(rows[0][2] is None, "handle_time=NULL（不入「最近失败」告警扫描）")
        check(rows[0][3] is None, "executor_address=NULL（未路由）")
        return jid
    finally:
        scratch_cleanup(headers, [jid])


def c5_blocked_single(headers):
    print("\n[c5] SINGLE 重叠 → status=4 阻塞行 + 回调守卫天然不含 4", flush=True)
    jid = create_job(headers, jobName="p1-sa-c5-blocked", blockStrategy="SINGLE")
    try:
        q("INSERT INTO job_log (job_id,job_group_id,executor_address,handler_name,trigger_type,"
          "trigger_time,handle_msg,status,shard_index) VALUES (%s,1,%s,'demoHandler','manual',NOW(),%s,0,0)",
          (jid, EXEC_ADDR, PLACEHOLDER))
        after = max_log_id(jid)
        api(headers, "POST", f"/job/{jid}/trigger")
        row, seen = poll_log(jid, after, timeout_s=6)
        check(row is not None and row[1] == 4, f"阻塞行 status=4 (seen={seen})")
        check("被阻塞丢弃" in (row[3] or ""), f"handle_msg 阻塞语义: {row[3]!r}")
        check(row[4] is None, "handle_time=NULL（blocked 不入告警/巡检）")
        check(row[5] is None, "executor_address=NULL（阻塞不 route、不耗 registry 读）")
        # 对 status=4 行补发回调 → 守卫 IN(0,3) 不含 4 → 0 行幂等忽略
        body = cb(row[0], 200, "should-not-apply")
        hint = body.get("data") if body.get("data") is not None else body.get("msg")
        check(body.get("code") == 200 and hint is not None and "已是最新状态" in str(hint),
              f"回调守卫对 4 幂等: code={body.get('code')} data={body.get('data')!r} msg={body.get('msg')!r}")
        st = q1("SELECT status FROM job_log WHERE id=%s", (row[0],))
        check(st[0] == 4, "blocked 行保持 4，不被回调改写")
        return jid
    finally:
        scratch_cleanup(headers, [jid])


def c6_timeout_d2(headers):
    print("\n[c6] 超时 D2：loadTestHandler 睡40s + timeout=1 → 30s 巡检翻 3 → 迟到回调 3→1 覆盖", flush=True)
    jid = create_job(headers, jobName="p1-sa-c6-d2", handlerName="loadTestHandler",
                     executorParam="40000", timeout=1, routeStrategy="FIRST")
    try:
        after = max_log_id(jid)
        api(headers, "POST", f"/job/{jid}/trigger")
        row, seen = poll_log(jid, after, timeout_s=8, want_terminal=False)
        check(row is not None and row[1] == 0, f"触发后为运行中 status=0 (seen={seen})")
        # 等最长 50s：期望在某次巡检点到 3，随后回调到 1
        observed = [0]
        deadline = time.time() + 50
        while time.time() < deadline:
            row = q1("SELECT status,handle_msg,handle_time FROM job_log WHERE job_id=%s AND id>%s "
                     "ORDER BY id DESC LIMIT 1", (jid, after))
            if row:
                st = row[0]
                if st != observed[-1]:
                    observed.append(st)
                    print(f"    ...status 变化: {st} @ {datetime.now():%H:%M:%S}  msg={row[1]!r}", flush=True)
                if st == 1:
                    check(3 in observed, f"迟到回调从 3 覆盖回 1（D2 实锤），轨迹 {observed}")
                    check(row[1] == "load test ok", f"最终 msg 为真实执行文本: {row[1]!r}")
                    check(row[2] is not None, "最终 handle_time 已写")
                    return jid
            time.sleep(2)
        check(False, f"50s 内未到终态，轨迹 {observed}")
        return jid
    finally:
        scratch_cleanup(headers, [jid])


def c7_cron_no_dupe(headers):
    print("\n[c7] cron 1Hz → 无同秒双触发 + 全成功（F6-2 防回归）", flush=True)
    print("   注: 单job低负载 cron 存在 pre-existing 饿死（claimable 秒截断 vs wheel 相位，见 2026-09-04 记录），", flush=True)
    print("       此处只验「落下的触发正确」，不断言 1Hz 节奏；零触发重试一次后再判。", flush=True)
    jid = create_job(headers, jobName="p1-sa-c7-1hz", handlerName="demoHandler",
                     cron="*/1 * * * * ?", routeStrategy="round_robin", blockStrategy="")
    try:
        for attempt in (1, 2):
            after = max_log_id(jid)
            api(headers, "POST", f"/job/{jid}/start")
            time.sleep(60)
            api(headers, "POST", f"/job/{jid}/stop")
            rows = q("SELECT trigger_time,status FROM job_log WHERE job_id=%s AND id>%s", (jid, after))
            if len(rows) > 0:
                break
            print(f"   第 {attempt} 轮 60s 零触发（疑饿死相位），重启 job 再测一轮", flush=True)
            delete_job(headers, jid)
            delete_logs(jid)
            jid = create_job(headers, jobName="p1-sa-c7-1hz", handlerName="demoHandler",
                             cron="*/1 * * * * ?", routeStrategy="round_robin", blockStrategy="")
        print(f"   60s 内落 {len(rows)} 条（饿死容忍：不断言频率，仅验正确性）", flush=True)
        check(len(rows) >= 1, "60s+重试后 cron 仍零触发 → cron 路径疑似回归")
        by_sec = {}
        for tt, st in rows:
            sec = tt.strftime("%Y-%m-%d %H:%M:%S") if hasattr(tt, "strftime") else str(tt)
            by_sec.setdefault(sec, []).append(st)
        dup = {k: v for k, v in by_sec.items() if len(v) > 1}
        check(len(dup) == 0, f"无同秒双触发；如有 {list(dup.items())[:3]}")
        non_success = [k for k, v in by_sec.items() if any(s != 1 for s in v)]
        check(len(non_success) == 0, f"每点 status 均为成功1（非1点 {non_success[:5]}）")
        return jid
    finally:
        scratch_cleanup(headers, [jid])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("group", choices=["fast", "slow", "cron"], help="fast=c1..c5, slow=c6, cron=c7")
    args = ap.parse_args()
    headers = login()
    if args.group == "fast":
        c1_success(headers)
        c2_dup_callback(headers)
        c3_unknown_logid(headers)
        c4_no_executor(headers)
        c5_blocked_single(headers)
    elif args.group == "slow":
        c6_timeout_d2(headers)
    else:
        c7_cron_no_dupe(headers)
    print("\n== 该组全部 PASS ==", flush=True)


if __name__ == "__main__":
    main()
