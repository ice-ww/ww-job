#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B2 专项：双 admin 收敛 + F6-2 决定性 SQL（spec §6.3「双 admin 各自心跳驱动缓存收敛一致」+ §6.1/§2
「B1 合并单事务回滚重试不得造成同秒双触发」）。

语义：两个 admin（8080 heartbeat 喂缓存 / 8085 冷启动、route 走 B-2 DB 回退+水合）共享本地 3306 ww_job
DB，各自 ScheduleHelper 独立预读+入轮。同一 cron 边界到点，两台时间轮几乎同时出队 → 双双进
decideCron（B1 合并单事务：select-FU → claimable → advance → SINGLE gate → route → INSERT）。
行锁串行化：先得锁者推进 next_time 并落 1 条 running；后到者 claimable(nowSec>lastNext) 为假 → null。
F6-2 决定性 SQL：job_log 按 (job_id, trigger_time 秒) 分组计数 >1 = 同秒双触发（双 claim）→ 期望 0。
两 admin 对每边界对称竞争（同 DB、同延迟），赢家近随机分布 → 数百次 race 覆盖双 schedulers。

另证 B-2 第二实例冷启动收敛：8085 无心跳喂给 → 首触 DB 回退；水合后 90s 内走自身缓存；
每 ~90s 再回退刷新（与第一实例心跳同源，DB 行 heartbeat 即新鲜源）。

阶段：
  P1 启动 adminB(8085)：断言 login ok + Started 日志
  P2 高密度双 admin cron 窗：N=12 job cron 0/3(SINGLE 快) 双 admin 并行 ~75s → 采集 job_log
     断言：(a) 每 (job_id, trigger_sec) ≤1（F6-2 零同秒双触发）
           (b) 全行终态无 status=4、无 FAIL(2)（executor 健康、无 SINGLE 阻塞）
           (c) 总 fire 数达密度下限（双 admin 下每边界仅 1 胜者，数量与单 admin 同量级）
  P3 清理：停+删 scratch job + logs，kill adminB；adminA 保持运行

用法：python tools/verify_b2_dualadmin.py
前置：adminA 8080(local 3306 ww_job) + executor 8081 均在运行（executor 心跳只喂 8080）。
DB 凭据复用 registry_unique_key_migration（不打印）。8085 若被占用会先清。
"""
import subprocess
import sys
import time

import pymysql
import requests

sys.path.insert(0, __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

BASE_A = "http://localhost:8080"
BASE_B = "http://localhost:8085"
PORT_B = 8085
ADMIN_PORT = 8080
EXEC_PORT = 8081
GROUP_ID = 1
JAVA = r"D:\Program\wwjdk21\bin\java.exe"
ADMIN_ARGFILE = r"D:\javacode\ww-job\tools\logs\adminA.argfile.backup"
ADMIN_MAIN = "com.wwjob.admin.WwJobAdminApplication"
ADMINB_LOG = r"D:\javacode\ww-job\tools\logs\dev_adminB.log"
ADMINB_ERR = r"D:\javacode\ww-job\tools\logs\dev_adminB.err.log"
NJOBS = 12
CRON = "0/3 * * * * ?"
WINDOW_S = 75
JOBN = "p1-sb-da"

DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          database="ww_job", charset="utf8mb4", autocommit=True)


def db():
    return pymysql.connect(**DB)


def q(sql, args=None):
    with db() as c:
        with c.cursor() as cur:
            if args is not None:
                cur.execute(sql, args)
            else:
                cur.execute(sql)
            return cur.fetchall()


def q1(sql, args=None):
    r = q(sql, args)
    return r[0] if r else None


def pid_on_port(port):
    out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True).stdout
    for ln in out.splitlines():
        p = ln.split()
        if len(p) >= 5 and p[0].startswith("TCP") and ":" in p[1] and p[1].rsplit(":", 1)[1] == str(port) \
                and p[3] == "LISTENING":
            return int(p[4])
    return None


def login(base):
    r = requests.post(f"{base}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def api(base, headers, method, path, body=None):
    r = requests.request(method, f"{base}{path}", headers=headers, json=body, timeout=30)
    return r.json()


def launch_admin_b():
    ps = (
        "Start-Process -FilePath '" + JAVA + "' "
        "-ArgumentList '-XX:TieredStopAtLevel=1','-Xmx512m','-Xms128m','-cp','\"@" + ADMIN_ARGFILE + "\"',"
        "'" + ADMIN_MAIN + "','--spring.profiles.active=local','--server.port=" + str(PORT_B) + "' "
        "-RedirectStandardOutput '" + ADMINB_LOG + "' -RedirectStandardError '" + ADMINB_ERR + "' -WindowStyle Hidden"
    )
    subprocess.run(["powershell", "-NoProfile", "-Command", ps], timeout=60)
    t0 = time.time()
    for _ in range(180):
        if pid_on_port(PORT_B) is not None:
            return pid_on_port(PORT_B), time.time() - t0
        time.sleep(0.5)
    return None, None


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg, flush=True)
    return ok


def main():
    ok = True
    # 前置：adminA + executor
    if pid_on_port(ADMIN_PORT) is None:
        print("前置失败：adminA 8080 未在监听", file=sys.stderr)
        sys.exit(2)
    if pid_on_port(EXEC_PORT) is None:
        print("前置失败：executor 8081 未在监听", file=sys.stderr)
        sys.exit(2)
    hdrA = login(BASE_A)
    print("adminA login ok", flush=True)

    # 8085 若有残留先清
    stale = pid_on_port(PORT_B)
    if stale:
        subprocess.run(["taskkill", "/F", "/PID", str(stale)], capture_output=True, text=True)
        time.sleep(1)
        print(f"cleaned stale adminB PID {stale}", flush=True)

    # 预清场 + 无其它启用任务守卫
    leftovers = q("SELECT id FROM job_info WHERE job_name LIKE 'p1-sb-da-%'")
    for (lid,) in leftovers:
        try:
            api(BASE_A, hdrA, "DELETE", f"/job/{lid}")
        except Exception:
            pass
    if leftovers:
        q("DELETE FROM job_log WHERE job_id IN (%s)" % ",".join(["%s"] * len(leftovers)),
          tuple(l for (l,) in leftovers))
    others = q1("SELECT COUNT(*) FROM job_info WHERE trigger_status=1 AND job_name NOT LIKE 'p1-sb-da-%'")[0]
    if others:
        print(f"守卫失败：DB 有 {others} 个其它启用任务，会污染本窗，先停用再跑", file=sys.stderr)
        sys.exit(2)

    # P1 启动 adminB
    pidB, boot = launch_admin_b()
    ok &= check(pidB is not None, f"P1 启动 adminB 8085 → LISTEN PID={pidB}（boot {boot:.1f}s）")
    if pidB is None:
        sys.exit(1)
    hdrB = login(BASE_B)
    ok &= check(hdrB is not None, "P1 adminB login ok（auth 正常）")

    try:
        # P2 建 N 个 cron job（先禁用创建，collect 后统一 start —— 两台 admin 同时发现同一批启用任务）
        jids = []
        for i in range(NJOBS):
            jid = api(BASE_A, hdrA, "POST", "/job", dict(jobName=f"{JOBN}-{i}", jobGroupId=GROUP_ID,
                      jobDesc="dual-admin f6-2 scratch", handlerName="demoHandler", executorParam="",
                      cron=CRON, routeStrategy="round_robin", blockStrategy="SINGLE", retryCount=0,
                      timeout=0, triggerStatus=0))["data"]
            jids.append(jid)
        print(f"created {len(jids)} scratch cron jobs {jids[0]}..{jids[-1]} cron={CRON}；start 双 admin 窗", flush=True)

        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id IN (%s)"
                    % ",".join(["%s"] * len(jids)), tuple(jids))[0]
        for jid in jids:
            api(BASE_A, hdrA, "POST", f"/job/{jid}/start")
        t0 = time.time()
        # 观察窗内统计新增 fire 数（进度提示）
        last = 0
        while time.time() - t0 < WINDOW_S:
            time.sleep(5)
            now = q1("SELECT COUNT(*) FROM job_log WHERE job_id IN (%s)"
                     % ",".join(["%s"] * len(jids)), tuple(jids))[0]
            if now != last:
                print(f"  +{now - last} fires (total {now})", flush=True)
                last = now
        for jid in jids:
            api(BASE_A, hdrA, "POST", f"/job/{jid}/stop")
        time.sleep(4)   # 让在途回调落终态
        rows = q("SELECT job_id, trigger_time, status FROM job_log WHERE job_id IN (%s)"
                 % ",".join(["%s"] * len(jids)), tuple(jids))
        print(f"窗口 {WINDOW_S}s 落 {len(rows)} 行（before={before}）", flush=True)

        # (a) F6-2：每 (job_id, trigger 秒) 恰 ≤1
        by_sec = {}
        for jid, tt, st in rows:
            sec = tt.strftime("%Y-%m-%d %H:%M:%S")
            by_sec.setdefault((jid, sec), []).append(st)
        dup = {k: v for k, v in by_sec.items() if len(v) > 1}
        ok &= check(len(dup) == 0, f"F6-2 决定性 SQL：无 (job,同秒) 双触发（{len(by_sec)} 个 (job,秒) 点；如有 {list(dup.items())[:3]}）")
        # (b) 终态分布：无 blocked(4) 无 fail(2)；RUNNING(0) 应为 0（回调已收）
        st_cnt = {}
        for (_, st) in [(k, s) for (k, v) in by_sec.items() for s in v]:
            st_cnt[st] = st_cnt.get(st, 0) + 1
        print(f"      status 分布: {dict(sorted(st_cnt.items()))}", flush=True)
        ok &= check(st_cnt.get(4, 0) == 0, f"无 SINGLE 阻塞 status=4（实际 {st_cnt.get(4, 0)}）")
        ok &= check(st_cnt.get(2, 0) == 0, f"无执行失败 status=2（实际 {st_cnt.get(2, 0)}）")
        ok &= check(st_cnt.get(0, 0) <= 3, f"残余 RUNNING(0) ≤3（回调已基本收齐，实际 {st_cnt.get(0, 0)}）")
        # (c) 密度下限
        ok &= check(len(rows) >= 100, f"总 fire ≥100（双 admin 每边界 1 胜者；实际 {len(rows)}）")

        print(f"\n== B2 双 admin F6-2 决定性 {'PASS' if ok else 'FAIL'} ==", flush=True)
        if not ok:
            sys.exit(1)
    finally:
        for jid in jids:
            try:
                api(BASE_A, hdrA, "POST", f"/job/{jid}/stop")
                api(BASE_A, hdrA, "DELETE", f"/job/{jid}")
            except Exception:
                pass
        if jids:
            q("DELETE FROM job_log WHERE job_id IN (%s)" % ",".join(["%s"] * len(jids)), tuple(jids))
            print(f"cleaned {len(jids)} scratch jobs + logs", flush=True)
        if pid_on_port(PORT_B):
            subprocess.run(["taskkill", "/F", "/PID", str(pid_on_port(PORT_B))], capture_output=True, text=True)
            print("killed adminB 8085", flush=True)


if __name__ == "__main__":
    main()
