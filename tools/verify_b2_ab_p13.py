#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P13：Stage B (B1+B2) 后 D=300 A/B 对照 P12（spec §6.4）。

配方 = 复刻 P12：loadtest 环境单 admin、enable 1-3000、3min 观察、disable 冻结、双口径互证。
上报：密度（trigger_time 冻结口径 + 观察插入口径）、per-sec min/max/空秒、尾部均值、
status 分布、distinct、同秒双触发、行锁 waits、DB conns、DB往返/s（cron 口径 5×密度）。
对照 P12：密度 166.9/s、per-sec min61/max271、窗末尾段 ~208/s、distinct=3000、同秒双触发=0。

诚实标注：本档相对 P12（2026-09-04 11:30，Stage A）主变量 = B1+B2（决策 2tx→1tx 合并 +
route 0 DB 内存缓存）。loadtest DB schema 与 dev 逐列相同（已验），环境其余同 P12（池30、双 fsync 关）。
吞吐如实上报，不预设结论；断言仅卡正确性红线（同秒双触发=0 / distinct=3000 / 零空秒 / 无失败态）。

流程：
  P0 前置：3307 可达、0 enabled、批 1-3000 全 loadTestHandler、TRUNCATE job_log
  P1 拓扑切换：kill dev admin(8080) → launch loadtest admin(profile=loadtest) → login →
     等 executor 心跳注册行新鲜（B2 缓存预热前提）
  P2 冒烟：手动 trigger job 1 → 终态 status=1（hot path 对 loadtest DB 的 schema/派发门）
  P3 窗口：enable 1-3000 → settle → window 180s（每 10s 插入口径 + Threads_connected 采样）
  P4 冻结：disable 1-3000 → 等行数稳定 → trigger_time 窗统计
  P5 复原(finally)：确保 1-3000 disabled → kill loadtest admin → relaunch dev admin(local) 于 8080
     （executor 30s 内自动回注 dev 3306；3307 容器保持运行、0 enabled）

用法：python tools/verify_b2_ab_p13.py
前置：Docker 3307 容器 ww-job-loadtest-mysql 运行；dev admin 8080 + executor 8081 运行中
（本工具切拓扑并在 finally 复原 dev admin）。DB：3307 = root/root（压测专用测试凭据）。
"""
import subprocess
import sys
import time
from collections import Counter
from datetime import datetime, timedelta

import pymysql
import requests

BASE = "http://localhost:8080"
PORT = 8080
JAVA = r"D:\Program\wwjdk21\bin\java.exe"
ADMIN_ARG = r"D:\javacode\ww-job\tools\logs\adminA.argfile.backup"
ADMIN_MAIN = "com.wwjob.admin.WwJobAdminApplication"
LT_LOG = r"D:\javacode\ww-job\tools\logs\dev_admin_loadtest.log"
LT_ERR = r"D:\javacode\ww-job\tools\logs\dev_admin_loadtest.err.log"
DEV_LOG = r"D:\javacode\ww-job\tools\logs\dev_admin.log"
DEV_ERR = r"D:\javacode\ww-job\tools\logs\dev_admin.err.log"
ID_MIN, ID_MAX = 1, 3000
WINDOW_S = 180
DB = dict(host="127.0.0.1", port=3307, user="root", password="root",
          database="ww_job_loadtest", charset="utf8mb4", autocommit=True)


def db():
    return pymysql.connect(**DB)


def q(sql, args=None):
    with db() as c:
        with c.cursor() as cur:
            cur.execute(sql, args) if args is not None else cur.execute(sql)
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


def login(base=BASE):
    r = requests.post(f"{base}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=10)
    assert r.json().get("code") == 200, f"登录失败: {r.text}"
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def launch_admin(profile, log, err):
    ps = (
        "Start-Process -FilePath '" + JAVA + "' "
        "-ArgumentList '-XX:TieredStopAtLevel=1','-Xmx512m','-Xms128m','-cp','\"@" + ADMIN_ARG + "\"',"
        "'" + ADMIN_MAIN + "','--spring.profiles.active=" + profile + "' "
        "-RedirectStandardOutput '" + log + "' -RedirectStandardError '" + err + "' -WindowStyle Hidden"
    )
    subprocess.run(["powershell", "-NoProfile", "-Command", ps], timeout=60)
    t0 = time.time()
    for _ in range(240):
        if pid_on_port(PORT) is not None:
            return pid_on_port(PORT), time.time() - t0
        time.sleep(0.5)
    return None, None


def kill_pid(pid):
    subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True, text=True)
    for _ in range(30):
        if pid_on_port(PORT) is None:
            return
        time.sleep(0.5)


def registry_fresh():
    # heartbeat_time 由 MySQL 容器 UTC 写入 → 用 UTC_TIMESTAMP() 对齐（F8-4 纪律）
    return q1("SELECT COUNT(*) FROM job_registry WHERE heartbeat_time >= UTC_TIMESTAMP() - INTERVAL 20 SECOND")[0]


def fire_and_wait(headers, jid, before_id, timeout=15):
    r = requests.post(f"{BASE}/job/{jid}/trigger", headers=headers, timeout=10)
    assert r.json().get("code") == 200, f"trigger {jid} 失败: {r.text}"
    deadline = time.time() + timeout
    while time.time() < deadline:
        row = q1("SELECT status FROM job_log WHERE job_id=%s AND id>%s ORDER BY id DESC LIMIT 1",
                 (jid, before_id))
        if row and row[0] in (1, 2, 4):
            return row[0]
        time.sleep(0.5)
    return None


def batch(headers, action, start=ID_MIN, stop=ID_MAX):
    """action in {'start','stop'}；单条 keep-alive Session 批量发 HTTP。"""
    s = requests.Session()
    s.headers.update(headers)
    n = 0
    for jid in range(start, stop + 1):
        r = s.post(f"{BASE}/job/{jid}/{action}", timeout=15)
        if r.status_code != 200 or r.json().get("code") != 200:
            print(f"  FAIL at id={jid}: HTTP {r.status_code} {r.text}", flush=True)
            sys.exit(1)
        n += 1
        if n % 500 == 0:
            print(f"  {action} {n}/3000...", flush=True)
    return n


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg, flush=True)
    return ok


def gstatus(name):
    return q1("SHOW GLOBAL STATUS LIKE %s", (name,))[1]


def main():
    ok = True
    # ---- P0 前置 ----
    if pid_on_port(PORT) is None:
        print("前置失败：8080 无 admin（需 dev admin 先起）", file=sys.stderr)
        sys.exit(2)
    try:
        q1("SELECT COUNT(*) FROM job_info")
    except Exception as e:
        print(f"前置失败：3307 不可达: {e}", file=sys.stderr)
        sys.exit(2)
    en = q1("SELECT COUNT(*) FROM job_info WHERE trigger_status=1")[0]
    if en:
        print(f"前置失败：loadtest 已有 {en} 个启用任务，先手动 drain", file=sys.stderr)
        sys.exit(2)
    try:
        q("TRUNCATE TABLE job_log")
    except Exception:
        q("DELETE FROM job_log")
    print("P0 前置 ok：3307 可达、0 enabled、job_log 已清空", flush=True)

    summary = None
    try:
        # ---- P1 拓扑切换 ----
        dev_pid = pid_on_port(PORT)
        print(f"P1 拓扑切换：kill dev admin PID {dev_pid} → launch loadtest admin", flush=True)
        kill_pid(dev_pid)
        pid, boot = launch_admin("loadtest", LT_LOG, LT_ERR)
        ok &= check(pid is not None, f"P1 launch loadtest admin → 8080 LISTEN PID={pid}（boot {boot if boot else -1:.1f}s）")
        if pid is None:
            sys.exit(1)
        hdrs = login()
        print("P1 loadtest admin login ok", flush=True)
        t0 = time.time()
        while time.time() - t0 < 70:
            if registry_fresh():
                break
            time.sleep(3)
        ok &= check(registry_fresh() >= 1,
                    f"P1 executor 心跳注册新鲜（{time.time() - t0:.0f}s）→ B2 组1 在线（缓存预热前提）")

        # ---- P2 冒烟 ----
        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log")[0]
        st = fire_and_wait(hdrs, 1, before)
        ok &= check(st == 1, f"P2 冒烟：手动 trigger job 1 → 终态 status=1（实际 {st}）——hot path schema/派发门")

        # 行锁/基线（enable 前）
        lock0 = int(gstatus("Innodb_row_lock_waits"))
        lock_t0 = int(gstatus("Innodb_row_lock_time"))

        # ---- P3 enable + 观察窗 ----
        print("P3 enable 1..3000（3000 × POST /start）...", flush=True)
        batch(hdrs, "start")
        print("P3 enabled；10s settle 后开 180s 冻结窗", flush=True)
        time.sleep(10)
        wb = datetime.now()
        prev = q1("SELECT COUNT(*) FROM job_log")[0]
        conns = []
        last = prev
        i = 0
        while datetime.now() < wb + timedelta(seconds=WINDOW_S):
            time.sleep(10)
            i += 1
            nowc = q1("SELECT COUNT(*) FROM job_log")[0]
            d = nowc - last
            print(f"  t+{i * 10:3d}s insert口径 += {d:5d}  ~{d / 10.0:.1f}/s  total={nowc}", flush=True)
            last = nowc
            if i % 3 == 0:
                tc = gstatus("Threads_connected")
                conns.append(int(tc))
                print(f"     [conn] Threads_connected={tc}", flush=True)
        t_end = datetime.now()
        lock1 = int(gstatus("Innodb_row_lock_waits"))
        lock_t1 = int(gstatus("Innodb_row_lock_time"))

        # ---- P4 drain + freeze ----
        print("P4 disable 冻结（drain 1..3000）...", flush=True)
        batch(hdrs, "stop")
        a, b = -1, -2
        while True:
            time.sleep(5)
            b = a
            a = q1("SELECT COUNT(*) FROM job_log")[0]
            if a == b:
                break
        print(f"P4 行数稳定 {a}（冻结完成）", flush=True)
        rows = q("SELECT job_id, trigger_time, status FROM job_log WHERE trigger_time>=%s AND trigger_time<=%s",
                 (wb, t_end))
        dur = (t_end - wb).total_seconds()
        print(f"P4 冻结窗 {wb.strftime('%H:%M:%S')}~{t_end.strftime('%H:%M:%S')}（{dur:.0f}s）内 {len(rows)} 行", flush=True)

        # ---- 统计 ----
        # 空秒/全秒 per-sec 只判「整秒全覆盖」区间 [ceil(wb) .. floor(t_end)]：
        # 窗口开/关落在半秒内的部分秒无法判定是否有真实空窗（P13 首跑曾把开窗部分秒误判为空秒）。
        density = len(rows) / dur
        by_sec = Counter(r[1].strftime("%Y-%m-%d %H:%M:%S") for r in rows)
        s0 = wb if wb.microsecond == 0 else (wb + timedelta(seconds=1)).replace(microsecond=0)
        s1 = t_end.replace(microsecond=0)
        full_secs = [str(s) for s in [s0 + timedelta(seconds=i) for i in range(int((s1 - s0).total_seconds()) + 1)]]
        empty = [x for x in full_secs if by_sec.get(x, 0) == 0]
        vals = [by_sec[s] for s in full_secs]
        secs_full = full_secs
        tail80 = secs_full[-80:]
        tail_density = sum(by_sec[s] for s in tail80) / len(tail80) if tail80 else 0
        st_cnt = Counter(r[2] for r in rows)
        distinct = len(set(r[0] for r in rows))
        perjob = Counter(r[0] for r in rows)
        lt2 = sum(1 for j, c in perjob.items() if c < 2)
        dup_hits = sum(1 for k, v in Counter((r[0], r[1].strftime("%Y-%m-%d %H:%M:%S")) for r in rows).items() if v > 1)
        lock_d = lock1 - lock0
        lock_td = lock_t1 - lock_t0
        roundtrips = 5.0 * density
        ins_density = (last - prev) / dur

        print("\n=== P13 Stage B D=300 A/B 报告 ===", flush=True)
        print(f"  冻结 trigger_time 口径密度: {density:.1f}/s（{len(rows)} 行 / {dur:.0f}s）", flush=True)
        print(f"  观察插入口径密度: ~{ins_density:.1f}/s（total {prev}→{last}）", flush=True)
        print(f"  per-sec: min={min(vals) if vals else 0} max={max(vals) if vals else 0} "
              f"avg={density:.1f} 空秒={len(empty)}", flush=True)
        print(f"  尾段 80s 均值（对照 P12 ~208/s）: {tail_density:.1f}/s", flush=True)
        print(f"  status 分布: {dict(sorted(st_cnt.items()))}", flush=True)
        print(f"  distinct job_id: {distinct}（<2 次触发 job: {lt2}）", flush=True)
        print(f"  同 (job,秒) 双触发组: {dup_hits}（F6-2 决定性 SQL）", flush=True)
        print(f"  Innodb_row_lock_waits Δ={lock_d}（{lock_d / len(rows) if rows else 0:.5f}/触发, time Δ={lock_td}ms）", flush=True)
        print(f"  Threads_connected(≈Hikari30+采样): min={min(conns) if conns else '-'} "
              f"max={max(conns) if conns else '-'}", flush=True)
        print(f"  DB 往返/s（cron 口径 5×密度）: {roundtrips:.0f}/s", flush=True)
        print(f"  对照 P12: 密度 166.9 → {density:.1f}/s（Δ{(density - 166.9) / 166.9 * 100:+.0f}%）；"
              f"per-sec min61/max271/0空秒 → min{min(vals) if vals else 0}/max{max(vals) if vals else 0}/{len(empty)}空秒；"
              f"尾段 ~208 → {tail_density:.1f}", flush=True)

        ok &= check(dup_hits == 0, "同秒双触发=0（F6-2 决定性）")
        ok &= check(distinct == 3000 and lt2 == 0, f"distinct=3000 无任务丢失（<2次 {lt2}）")
        ok &= check(len(empty) == 0, "零空秒（饱和无整秒缺口）")
        bad = {k: v for k, v in st_cnt.items() if k not in (1,)}
        ok &= check(not bad, f"status 全 success（异常 {bad}）")
        print(f"\n== P13 D=300 A/B（对照 P12）{'PASS' if ok else 'FAIL'} ==", flush=True)
        summary = dict(density=round(density, 1), tail80=round(tail_density, 1),
                       per_min=min(vals) if vals else 0, per_max=max(vals) if vals else 0,
                       empty=len(empty), st=dict(st_cnt), distinct=distinct, lt2=lt2, dup=dup_hits,
                       lock_wait_delta=lock_d, roundtrips_s=round(roundtrips, 1), ins=round(ins_density, 1))
        if not ok:
            sys.exit(1)
    finally:
        # ---- P5 复原 ----
        print("\nP5 复原：清理 loadtest 启用态 + 还原 dev admin...", flush=True)
        if pid_on_port(PORT) is not None:
            try:
                h = login()
                if q1("SELECT COUNT(*) FROM job_info WHERE trigger_status=1")[0] > 0:
                    batch(h, "stop")
                    print("  已 drain 剩余启用任务", flush=True)
            except Exception as e:
                print(f"  复原 drain 跳过: {e}", flush=True)
            p = pid_on_port(PORT)
            if p is not None:
                kill_pid(p)
                print(f"  killed loadtest admin PID {p}", flush=True)
        # 还原 dev admin(local)
        dp, dboot = launch_admin("local", DEV_LOG, DEV_ERR)
        if dp is not None:
            try:
                login()
                print(f"  dev admin(local) 还原 → 8080 LISTEN PID={dp}（boot {dboot:.1f}s，executor 30s 内回注 3306）", flush=True)
            except Exception as e:
                print(f"  还原后 login 失败: {e}", flush=True)
        else:
            print("  还原 dev admin 失败——请手动启动", flush=True)


if __name__ == "__main__":
    r = main()
    if r:
        print("P13_SUMMARY " + str(r), flush=True)
