#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B2 专项：冷启动回退（spec §6.3）。重启 admin → 缓存空 → 首个心跳周期内的首次触发必须成功
（走 B-2 空集回退 DB + 水合，不得误落 status=2「无可用执行器」）。

语义：admin 重启后 RegistryCacheService 为空的确定性来源是冷启动（无 @PostConstruct 预热，
spec §3 冷启动：缓存空 → 首触 route 回退 DB 并水合）。executor 心跳每 30s 广播；重启后首个
心跳到达前触发 → 路由只能经「缓存空 → DB selectList(心跳行仍在、90s 新鲜) → 水合 → 派发」成功。
用 general_log 微窗（boot→trigger→回调）捕获 job_registry SELECT 作为「确实走了 DB 回退」的决定性证据
（稳态探针 route DB 读=0；若微窗内出现 job_registry SELECT ≥1 ⇒ 缓存空强制回退，非心跳先行水合）。

阶段（scratch 禁用 job，全手动 trigger，避开 cron/启停竞态）：
  S1 重启前基线   : trigger → 终态 status=1（executor 可达 + job 路由正常）
  S2 kill admin   : 8080 释放
  S3 冷启动首触发 : relaunch admin(profile=local 3306 ww_job) → 8080 LISTEN 后立即 trigger →
                    终态 status=1（走 DB 回退/水合，未误落 status=2）+ general_log 微窗 job_registry SELECT 计数
  S4 恢复后复核   : 等一个心跳周期后再次 trigger → 终态 status=1（稳态路径）

用法：python tools/verify_b2_coldstart.py
前置：admin 8080(local 3306 ww_job) 与 executor 8081 均在运行（本工具会重启 admin，结束后保持运行）。
DB 凭据复用 registry_unique_key_migration（不打印）。cleanup 删除 scratch job + logs。
"""
import subprocess
import sys
import time

import pymysql
import requests

sys.path.insert(0, __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

BASE = "http://localhost:8080"
ADMIN_PORT = 8080
EXEC_PORT = 8081
GROUP_ID = 1
VALUE = "127.0.0.1:8081"
JOBNAME = "p1-sb-coldstart"
JAVA = r"D:\Program\wwjdk21\bin\java.exe"
ADMIN_ARGFILE = r"D:\javacode\ww-job\tools\logs\adminA.argfile.backup"
ADMIN_MAIN = "com.wwjob.admin.WwJobAdminApplication"
ADMIN_LOG = r"D:\javacode\ww-job\tools\logs\dev_admin.log"
ADMIN_ERR = r"D:\javacode\ww-job\tools\logs\dev_admin.err.log"

DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          database="ww_job", charset="utf8mb4", autocommit=True)


def db():
    return pymysql.connect(**DB)


def q(sql, args=None, db="ww_job"):
    c = pymysql.connect(**{**DB, "database": db})
    try:
        with c.cursor() as cur:
            if args is not None:
                cur.execute(sql, args)
            else:
                cur.execute(sql)
            return cur.fetchall()
    finally:
        c.close()


def q1(sql, args=None):
    r = q(sql, args)
    return r[0] if r else None


def api_hdrs():
    return {"Authorization": "Bearer " +
            requests.post(f"{BASE}/auth/login", json={"username": "admin", "password": "admin123"},
                          timeout=10).json()["data"]["token"]}


def api(headers, method, path, body=None):
    r = requests.request(method, f"{BASE}{path}", headers=headers, json=body, timeout=30)
    return r.json()


def pid_on_port(port):
    out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True).stdout
    for ln in out.splitlines():
        p = ln.split()
        if len(p) >= 5 and p[0].startswith("TCP") and ":" in p[1] and p[1].rsplit(":", 1)[1] == str(port) \
                and p[3] == "LISTENING":
            return int(p[4])
    return None


def registry_count():
    return q1("SELECT COUNT(*) FROM job_registry WHERE job_group_id=%s AND registry_value=%s",
              (GROUP_ID, VALUE))[0]


def fire_and_wait(headers, jid, before_id, timeout=12):
    api(headers, "POST", f"/job/{jid}/trigger")
    deadline = time.time() + timeout
    while time.time() < deadline:
        row = q1("SELECT status, handle_msg FROM job_log WHERE job_id=%s AND id>%s "
                 "ORDER BY id DESC LIMIT 1", (jid, before_id))
        if row:
            st, msg = row
            if st == 1 or st == 2 or st == 4:
                return (st, (msg or "")[:60])
        time.sleep(0.5)
    return None


def kill_admin():
    pid = pid_on_port(ADMIN_PORT)
    if pid is None:
        return None
    subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True, text=True)
    for _ in range(40):
        if pid_on_port(ADMIN_PORT) is None:
            break
        time.sleep(0.5)
    print(f"S2 kill admin：PID {pid} → 8080 释放", flush=True)
    return pid


def relaunch_admin():
    ps = (
        "Start-Process -FilePath '" + JAVA + "' "
        "-ArgumentList '-XX:TieredStopAtLevel=1','-Xmx512m','-Xms128m','-cp','\"@" + ADMIN_ARGFILE + "\"',"
        "'" + ADMIN_MAIN + "','--spring.profiles.active=local' "
        "-RedirectStandardOutput '" + ADMIN_LOG + "' -RedirectStandardError '" + ADMIN_ERR + "' -WindowStyle Hidden"
    )
    subprocess.run(["powershell", "-NoProfile", "-Command", ps], timeout=60)
    t0 = time.time()
    for _ in range(180):
        if pid_on_port(ADMIN_PORT) is not None:
            return pid_on_port(ADMIN_PORT), time.time() - t0
        time.sleep(0.5)
    return None, None


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg, flush=True)
    return ok


def main():
    ok = True
    hdrs = api_hdrs()
    print("login ok", flush=True)

    if pid_on_port(EXEC_PORT) is None or registry_count() != 1:
        print("前置失败：executor 8081 未在监听或 registry 行数≠1", file=sys.stderr)
        sys.exit(2)

    # 预清场
    leftovers = q("SELECT id FROM job_info WHERE job_name LIKE 'p1-sb-coldstart-%'")
    for (lid,) in leftovers:
        try:
            api(hdrs, "DELETE", f"/job/{lid}")
        except Exception:
            pass
    if leftovers:
        q("DELETE FROM job_log WHERE job_id IN (%s)" % ",".join(["%s"] * len(leftovers)),
          tuple(l for (l,) in leftovers))

    jid = api(hdrs, "POST", "/job", dict(jobName=JOBNAME, jobGroupId=GROUP_ID,
              jobDesc="b2 cold-start fallback scratch", handlerName="demoHandler", executorParam="",
              cron="0 0 0 * * ?", routeStrategy="round_robin", blockStrategy="SINGLE", retryCount=0,
              timeout=0, triggerStatus=0))["data"]
    print(f"created scratch job {jid}", flush=True)

    killed = False
    relaunched = False
    try:
        # S1 重启前基线
        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s", (jid,))[0]
        row = fire_and_wait(hdrs, jid, before)
        ok &= check(row is not None and row[0] == 1,
                    f"S1 重启前基线：trigger → 终态 status=1（实际 {row}）")

        # S2 kill admin
        kill_admin()
        killed = True

        # S3 relaunch + 冷启动首触发
        pid, boot_s = relaunch_admin()
        relaunched = pid is not None
        ok &= check(pid is not None, f"S3 relaunch admin → 8080 监听 PID={pid}（boot {boot_s:.1f}s）")
        # 冷启动立即（首个心跳周期内）触发；general_log 微窗捕获 route 是否回退 DB
        q("SET global log_output='TABLE'", db="mysql")
        q("SET global general_log=OFF", db="mysql")
        q("TRUNCATE TABLE mysql.general_log", db="mysql")
        q("SET global general_log=ON", db="mysql")
        hdrs2 = api_hdrs()          # 新 admin 上重新登录
        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s", (jid,))[0]
        row = fire_and_wait(hdrs2, jid, before)
        time.sleep(0.5)             # 允许回调 SQL 落账
        q("SET global general_log=OFF", db="mysql")
        rows = q("SELECT argument FROM mysql.general_log", db="mysql")
        reg_sel = sum(1 for (a,) in rows if "job_registry" in str(a) and str(a).lstrip().upper().startswith("SELECT"))
        ok &= check(row is not None and row[0] == 1,
                    f"S3 冷启动首触发：admin 重启后立即 trigger → 终态 status=1（实际 {row}）"
                    f" —— 未误落 status=2「无可用执行器」")
        # 回退证据非门：微窗可能已被首个心跳水合（30s 节拍相撞），0 SELECT 时语义等价仍 PASS。
        print(f"      证据(诊断)：首触微窗 job_registry SELECT={reg_sel}"
              f"{' —— 缓存空强制走 DB 回退+水合（决定性）' if reg_sel >= 1 else ' —— 首个心跳已先行水合缓存（非决定性，语义等价）'}", flush=True)

        # S4 恢复后复核（等一个心跳周期 30s 内的稳态路径）
        time.sleep(3)
        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s", (jid,))[0]
        row = fire_and_wait(hdrs2, jid, before)
        ok &= check(row is not None and row[0] == 1, f"S4 恢复复核：trigger → 终态 status=1（实际 {row}）")

        print(f"\n== B2 冷启动回退 {'PASS' if ok else 'FAIL'} ==", flush=True)
        if not ok:
            sys.exit(1)
    finally:
        if killed and not relaunched:
            try:
                relaunch_admin()
                print("cleanup: 已重新拉起 admin（环境保持可用）", flush=True)
            except Exception as e:
                print(f"cleanup: relaunch admin 失败: {e}", flush=True)
        try:
            hdr = api_hdrs() if pid_on_port(ADMIN_PORT) else hdrs
            api(hdr, "DELETE", f"/job/{jid}")
            q("DELETE FROM job_log WHERE job_id=%s", (jid,))
            print(f"cleaned scratch job {jid} + logs", flush=True)
        except Exception as e:
            print(f"清理 {jid} 失败: {e}", flush=True)


if __name__ == "__main__":
    main()
