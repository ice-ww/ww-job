#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B2 专项：executor 硬 kill → ≤90s 缓存与 DB 同语义驱逐 → 触发落「无可用执行器」；
重启 executor → 重注册 → 路由恢复（spec §6.3 executor kill 驱逐 / B-2 空集回退逐格对照）。

语义前提（Stage B：route 走本 admin 内存缓存，仅「组内无新鲜」时回退 DB）：
  executor 硬 kill（taskkill /F，不发 offline 广播）后，缓存与 DB 写点都没有「下线」通知，
  驱逐只能靠 90s 过期：RegistryCleaner(~10s) 删 heartbeat>90s 行 + 读侧 90s 新鲜过滤（同口径）。
  观测「两态对比」作为驱逐决定性指纹（同一触发点，job_log 两种形态只差驱逐有没有发生）：
    * 剔除前（kill 后立即，缓存仍新鲜）：route 仍派死地址 127.0.0.1:8081 → HTTP 连接拒绝 →
      job_log 由 dispatch 收尾 endRunning 置 status=2、handle_time 非空、msg 为连接异常（非「无可用执行器」）
      —— 证在路由死地址（缓存判在线），不是判无执行器；
    * 剔除后（距最后一次心跳 >90s）：缓存无新鲜 → 回退 DB（行被 cleaner 删除 / 被 90s 阈值过滤）→
      route 空 → decide INSERT status=2 handle_time NULL、msg「无可用执行器」（判无执行器，未派发）。
  重启 executor：register+心跳 → DB 行加回 + 缓存 touch（水合）→ 触发落 status=1。

阶段（同一 scratch 禁用 job，全手动 trigger——手动路径 decide 无 trigger_status 门，与
verify_registry_hardening 同一手法，规避 cron 相位噪声与 enable/disable 竞态）：
  S1 基线在线成功  : trigger → 终态 status=1（executor 可达，handler demoHandler 快成功）
  S2 kill 剔除前窗 : kill → 8081 释放 → trigger → status=2 且 msg 无「无可用执行器」、handle_time 非空
  S3 90s 驱逐      : 轮询 job_registry 行删除(≤200s) → 再等 ~15s 跨过缓存新鲜线 → trigger →
                     status=2 且 msg 含「无可用执行器」、handle_time NULL（缓存与 DB 一致判无执行器）
  S4 重启恢复      : 重launch executor → 8081 LISTEN + registry 行回填 → trigger → 终态 status=1

用法：
  python tools/verify_b2_eviction.py
前置：admin 8080(local, 3306 ww_job) 已启动且已注册 executor 8081；DB 凭据复用
  registry_unique_key_migration（读 application-local.yml，不打印）。
结束时 executor 保持运行（供后续冷启动/双 admin 测试），scratch job 与日志已清理。
"""
import subprocess
import sys
import time

import pymysql
import requests

sys.path.insert(0, __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

BASE = "http://localhost:8080"
EXEC_PORT = 8081
GROUP_ID = 1
VALUE = "127.0.0.1:8081"
JOBNAME = "p1-sb-evict"
JAVA = r"D:\Program\wwjdk21\bin\java.exe"
ARGFILE = r"D:\javacode\ww-job\tools\logs\executor.argfile.backup"
LOG = r"D:\javacode\ww-job\tools\logs\dev_executor.log"
ERRLOG = r"D:\javacode\ww-job\tools\logs\dev_executor.err.log"

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
                cur.execute(sql)   # 无参数不走 mogrify，LIKE 字面 % 不被吞
            return cur.fetchall()


def q1(sql, args=None):
    r = q(sql, args)
    return r[0] if r else None


def login():
    r = requests.post(f"{BASE}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=10)
    assert r.json().get("code") == 200, f"登录失败: {r.json()}"
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


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


def trigger(jid, headers):
    return api(headers, "POST", f"/job/{jid}/trigger")


def fire_and_wait(jid, headers, before_id, timeout=12):
    """手动触发后等该 fire 落终态。返回 (status, handle_time_is_null, msg) 或 None。
    手动路径：FAIL(无执行器) 在 INSERT 即终态(handle_time NULL)；投递失败由 dispatch 同步 endRunning(handle_time 置位)；
    成功路径异步回调 0→1。均轮询到终态。"""
    trigger(jid, headers)
    deadline = time.time() + timeout
    while time.time() < deadline:
        row = q1("SELECT status, handle_time, handle_msg FROM job_log "
                 "WHERE job_id=%s AND id>%s ORDER BY id DESC LIMIT 1", (jid, before_id))
        if row:
            st, ht, msg = row
            if st == 1:
                return (st, ht is None, (msg or "")[:60])
            if st == 2 and (ht is not None or (msg or "").strip() == "无可用执行器"):
                return (st, ht is None, (msg or "")[:60])
            if st == 4:
                return (st, ht is None, (msg or "")[:60])
        time.sleep(0.5)
    return None


def kill_executor():
    pid = pid_on_port(EXEC_PORT)
    if pid is None:
        print(f"S2 kill：executor({EXEC_PORT}) 未在监听，无需 kill", flush=True)
        return None
    r = subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True, text=True)
    print(f"S2 kill：executor PID {pid} taskkill -> rc={r.returncode} {r.stdout.strip()}", flush=True)
    # 等端口释放（kill 后 TCP 端口回收）
    for _ in range(30):
        if pid_on_port(EXEC_PORT) is None:
            break
        time.sleep(0.5)
    gone = pid_on_port(EXEC_PORT)
    print(f"S2 kill：8081 已释放（残余 PID={gone}）", flush=True)
    return pid


def relaunch_executor():
    ps = (
        "Start-Process -FilePath '" + JAVA + "' "
        "-ArgumentList '-XX:TieredStopAtLevel=1','-Xmx512m','-Xms128m','-cp','\"@" + ARGFILE + "\"',"
        "'com.wwjob.executor.samples.WwJobExecutorSamplesApplication' "
        "-RedirectStandardOutput '" + LOG + "' -RedirectStandardError '" + ERRLOG + "' -WindowStyle Hidden"
    )
    subprocess.run(["powershell", "-NoProfile", "-Command", ps], timeout=60)
    # 等 8081 监听
    for _ in range(120):
        if pid_on_port(EXEC_PORT) is not None:
            print(f"S4 relaunch：8081 监听于 PID {pid_on_port(EXEC_PORT)}", flush=True)
            return pid_on_port(EXEC_PORT)
        time.sleep(0.5)
    return None


def check(ok, msg):
    print(("PASS  " if ok else "FAIL  ") + msg, flush=True)
    return ok


def main():
    ok = True
    hdrs = login()
    print("login ok", flush=True)

    # 前置：executor 在听、registry 恰 1 行
    cur_pid = pid_on_port(EXEC_PORT)
    if cur_pid is None:
        print("前置失败：8081 无 executor 在监听——先启动 executor 再跑", file=sys.stderr)
        sys.exit(2)
    if registry_count() != 1:
        print(f"前置失败：registry {VALUE} 行数={registry_count()}（期望 1）", file=sys.stderr)
        sys.exit(2)

    # 预清场：遗留 p1-sb-evict job
    leftovers = q("SELECT id FROM job_info WHERE job_name LIKE 'p1-sb-evict-%'")
    for (lid,) in leftovers:
        try:
            api(hdrs, "DELETE", f"/job/{lid}")
        except Exception:
            pass
    if leftovers:
        q("DELETE FROM job_log WHERE job_id IN (%s)" % ",".join(["%s"] * len(leftovers)),
          tuple(l for (l,) in leftovers))
        print(f"preflight: 清理 {len(leftovers)} 个遗留 p1-sb-evict job", flush=True)

    jid = api(hdrs, "POST", "/job", dict(jobName=JOBNAME, jobGroupId=GROUP_ID,
              jobDesc="b2 eviction regression scratch", handlerName="demoHandler", executorParam="",
              cron="0 0 0 * * ?", routeStrategy="round_robin", blockStrategy="SINGLE", retryCount=0,
              timeout=0, triggerStatus=0))["data"]
    print(f"created scratch job {jid} (disabled, manual-trigger only)", flush=True)

    killed = False
    relaunched = False
    try:
        # ---- S1 基线在线成功 ----
        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s", (jid,))[0]
        row = fire_and_wait(jid, hdrs, before)
        ok &= check(row is not None and row[0] == 1,
                    f"S1 基线：executor 在线手动触发 → 终态 status=1（实际 {row}）")

        # ---- S2 kill 后剔除前窗（缓存仍新鲜 → 仍路由死地址 → 投递失败落账）----
        kill_executor()
        killed = True
        t_kill = time.time()
        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s", (jid,))[0]
        row = fire_and_wait(jid, hdrs, before)
        # status=2 且 msg 非「无可用执行器」且 handle_time 非空（dispatch 收尾 endRunning）
        ok &= check(row is not None and row[0] == 2 and row[1] is False and "无可用执行器" not in row[2],
                    f"S2 剔除前窗：kill 后立即触发 → status=2 投递失败(handle_time 置位, msg={row and row[2]!r})"
                    f"—— 缓存仍路由死地址，非判无执行器")

        # ---- S3 90s 驱逐：缓存与 DB 同语义剔除 ----
        t0 = time.time()
        deleted_at = None
        for _ in range(400):          # ≤200s
            if registry_count() == 0:
                deleted_at = time.time() - t0
                break
            time.sleep(0.5)
        print(f"S3 驱逐：registry 行删除于 kill 后 {deleted_at:.0f}s" if deleted_at else "S3 驱逐：registry 行 200s 未被 cleaner 删除（异常）",
              flush=True)
        # cleaner 删行只保证 DB 空；缓存新鲜线是「最后一次心跳 +90s」，比删行晚 0~10s（cleaner 粒度），
        # 再等 15s 保证缓存项也过 90s 新鲜线 → 触发必走 空缓存→DB 回退(空)→「无可用执行器」。
        time.sleep(15 if deleted_at else 0)
        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s", (jid,))[0]
        row = fire_and_wait(jid, hdrs, before)
        reg_now = registry_count()
        ok &= check(row is not None and row[0] == 2 and row[1] is True and "无可用执行器" in row[2],
                    f"S3 驱逐后：触发 → status=2「无可用执行器」(handle_time NULL, msg={row and row[2]!r})"
                    f" —— 缓存与 DB 一致判无执行器")
        ok &= check(reg_now == 0, f"S3 驱逐后 registry 行数=0（DB 侧已剔除，实际 {reg_now}）")

        # ---- S4 重启恢复 ----
        pid = relaunch_executor()
        relaunched = pid is not None
        ok &= check(pid is not None, f"S4 relaunch executor → 8081 监听（PID={pid}）")
        # 等注册行回填（register 立即 + 心跳 ≤30s）
        seen_at = None
        for _ in range(100):
            if registry_count() == 1:
                seen_at = time.time()
                break
            time.sleep(0.5)
        back_delay = (seen_at - t_kill) if seen_at else None
        ok &= check(seen_at is not None,
                    f"S4 注册恢复：registry {VALUE} 行回填（kill 后 ~{back_delay:.0f}s 内）")
        before = q1("SELECT COALESCE(MAX(id),0) FROM job_log WHERE job_id=%s", (jid,))[0]
        row = fire_and_wait(jid, hdrs, before)
        ok &= check(row is not None and row[0] == 1,
                    f"S4 恢复后：触发 → 终态 status=1（实际 {row}）—— 路由恢复正常")

        print(f"\n== B2 executor-kill 驱逐 + 重启恢复 {'PASS' if ok else 'FAIL'} ==", flush=True)
        if not ok:
            sys.exit(1)
    finally:
        # executor 保持运行（供后续冷启动/双 admin 测试）
        if killed and not relaunched:
            try:
                relaunch_executor()
                print("cleanup: 测试失败但已确保 executor 重新拉起（环境保持可用）", flush=True)
            except Exception as e:
                print(f"cleanup: relaunch executor 失败: {e}", flush=True)
        try:
            api(hdrs, "DELETE", f"/job/{jid}")
            q("DELETE FROM job_log WHERE job_id=%s", (jid,))
            print(f"cleaned scratch job {jid} + logs", flush=True)
        except Exception as e:
            print(f"清理 {jid} 失败: {e}", flush=True)


if __name__ == "__main__":
    main()
