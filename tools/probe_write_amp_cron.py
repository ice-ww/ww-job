#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P1 Stage B cron 快路径写放大探针：抓一批 ~N 个 */5 cron(SINGLE 快、稳态) 触发的实达 SQL，
锚定「窗口内 job_log INSERT 数 F（每 fire 恰 1 条 running 日志）」按比例分类，
验证 cron 触发线程口径收敛到 spec 阶段B 账本。

口径（触发线程/每 fire）：
  job_info 读  = 合并单事务内 1 次 SELECT ... WHERE id=.. FOR UPDATE  —— B1 决定性证据（Stage A 双事务为 2）
  job_info 写  = 2（锁内 advance trigger_next_time + ack touch trigger_last_time）
  job_log      = 1 COUNT(*) running 门 + 1 INSERT
  job_registry = route 读 ≈0（B2 内存缓存命中，冷启动回退除外）
  ⇒ 触发线程核心 ≈ 5（B1+B2）  /  ≈6、job_info 读=2（仅 B2，Stage A cron 双事务中间态）
异步回调 UPDATE job_log（completeById）≈1/每 fire，边沿可欠计，仅参考。

拒绝噪声与校正（关键，B2 中间态在 general_log 下必现）：轮盘按 ~1010ms 粗粒度推进，Windows sleep
偶发早醒使实际节拍 < 1000ms → 入轮安全量 +TICK_MS 不足 → fire 落进边界秒内（nowSec==boundary）→
claimable 拒绝（白花 1 把锁读、无 INSERT、advance 不动）→ 下轮 scheduleLoop 见 next<now 做 catch-up
推进（多 1 次 advance，该边界 fire 被跳过）。该噪声是调度器固有行为（B1/B2 都改不了；根治是入轮
安全量 +2 ticks，见 ScheduleHelper），会污染裸每-fire 口径。但拒绝数恒 = 追赶数 = advance − F
（脏窗实测精确相等），把 FU/advance 各剔拒绝数后，每-fire 口径精确回归「成功 fire」基准
（B2：读 2/核心 6；B1：读 1/核心 5）；FU 侧与 advance 侧两条推导做交叉校验（模型自洽断言），
不一致即 FAIL（有非拒绝的异常锁读被剔除逻辑吞掉，必须暴露）。默认 N=8 仅用于压低噪声本身。

事务边界（2tx→1tx）：合并后每 fire 单段 @Transactional（general_log 可见 SET autocommit=0/commit 成对，
B2-only 每 fire 两对、B1 一对，作佐证打印；硬断言用 FOR UPDATE 计数，不受驱动差异影响）。

用法：
  python tools/probe_write_amp_cron.py               # 默认：对 B1 已加载的 admin 断言 GREEN（读=1、core≈5）
  python tools/probe_write_amp_cron.py --mode b2     # 对 B2-only admin 断言中间基线（读=2、core≈6）

前置：admin 8080(local, 3306 ww_job) + executor samples 8081 已启动；DB 内不得有遗留「启用中」的
sharding 任务（会污染 F）。本工具建 N 个临时 */5 任务，跑完 finally 清理。
凭据复用 registry_unique_key_migration（不打印）。
"""
import argparse
import sys
import time

import pymysql
import requests

sys.path.insert(0, __file__.rsplit("\\", 1)[0] or ".")
import registry_unique_key_migration as mig  # noqa: E402

BASE = "http://localhost:8080"
DB = dict(host="localhost", port=3306, user="root", password=mig.read_local_password(),
          charset="utf8mb4", autocommit=True)
DEFAULT_JOBS = 8
DEFAULT_WINDOW_S = 45


def q(sql, args=None, db=None):
    c = pymysql.connect(**{**DB, "database": db or "ww_job"})
    try:
        with c.cursor() as cur:
            if args is not None:
                cur.execute(sql, args)
            else:
                cur.execute(sql)   # 无参数时不走 mogrify，SQL 内字面 %（如 LIKE 'p1-sb-wa-%'）不被吞
            return cur.fetchall()
    finally:
        c.close()


def q1(sql, args=None):
    r = q(sql, args)
    return r[0] if r else None


def txt(v):
    return v.decode("utf-8", "replace") if isinstance(v, (bytes, bytearray)) else str(v)


def clean(s):
    return " ".join(txt(s).split())


def login():
    r = requests.post(f"{BASE}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


DEFAULT_CRON = "*/5 * * * * ?"
# 周期 */5：稳态实测 fire 恰落边界下一秒、零拒绝零追赶（见 docstring）。小批量避免同边界齐射堆积 churn。


def classify(s):
    """按 SQL 文本形态分类，返回标签列表。通用于 B2-only 与 B1（形态本身未变，只变每 fire 次数）。"""
    tags = []
    up = s.upper()
    is_sel = up.startswith("SELECT")
    has_info = "job_info" in s
    has_log = "job_log" in s
    has_reg = "job_registry" in s
    if has_info and is_sel:
        if "FOR UPDATE" in up:
            tags.append("info_fu_read")            # 决策锁读（B1 合并后每 fire 1 次；Stage A 双事务 2 次）
        elif "trigger_status" in s and "trigger_next_time" in s:
            tags.append("info_scan")               # scheduleLoop 每秒预读（与触发无关，背景剔除）
        else:
            tags.append("info_other_read")
    if has_info and up.startswith("UPDATE"):
        if "trigger_next_time" in s:
            tags.append("info_advance")            # 锁内推进（claim）
        elif "trigger_last_time" in s:
            tags.append("info_touch")              # ack touch（dispatch，事务外）
        else:
            tags.append("info_other_update")       # 契约检查应=0
    if has_log and is_sel and "COUNT(*)" in up and "job_id" in s:
        tags.append("log_count_running")           # SINGLE 门（每 fire 1）
    if has_log and up.startswith("INSERT"):
        tags.append("log_insert")                  # 每 fire 恰 1（running/blocked/fail）——F 锚
    if has_log and up.startswith("UPDATE"):
        tags.append("log_update")                  # 异步回调/收尾，边沿可欠计
    if has_reg and is_sel:
        tags.append("reg_select")                  # route 读：B2 后应≈0（冷启动回退除外）
    if has_reg and up.startswith("INSERT"):
        tags.append("reg_upsert")                  # executor 心跳 upsert（周期背景，非每 fire）
    if has_reg and up.startswith("DELETE"):
        tags.append("reg_clean")                   # RegistryCleaner 周期 DELETE（无陈旧行时 0 行，仍达库）
    if s in ("SET autocommit=0", "commit", "rollback", "SET autocommit=1"):
        tags.append("tx_" + clean(s))              # 事务边界佐证（驱动相关，仅打印）
    return tags


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["green", "b2"], default="green",
                    help="green=B1 断言(读≈1/core≈5)；b2=B2-only 中间基线断言(读≈2/core≈6)")
    ap.add_argument("--jobs", type=int, default=DEFAULT_JOBS)
    ap.add_argument("--seconds", type=int, default=DEFAULT_WINDOW_S)
    ap.add_argument("--cron", default=DEFAULT_CRON,
                    help=f"cron 表达式（默认 {DEFAULT_CRON}；勿用 */1——轮盘节拍相移产生 claimable 拒绝噪音）")
    ap.add_argument("--print-samples", action="store_true", help="每类打印前几条样例 SQL")
    ap.add_argument("--force", action="store_true",
                    help="DB 存在其它启用任务时仍强跑（守卫断言可能 FAIL，仅诊断用）")
    a = ap.parse_args()
    N, W = a.jobs, a.seconds
    mode = a.mode
    expect_fu = 2 if mode == "b2" else 1
    expect_core = 6 if mode == "b2" else 5
    print(f"mode={mode}: 期望 job_info FOR UPDATE 读 ≈{expect_fu}/fire、触发线程核心 ≈{expect_core}/fire")

    # 前置：executor 在线（route 非空才能让 touch/回调走通），且无启用的 sharding cron 干扰
    online = q1("SELECT COUNT(*) FROM job_registry WHERE job_group_id=1 "
                "AND heartbeat_time >= DATE_SUB(NOW(), INTERVAL 90 SECOND)")[0]
    if not online:
        print("executor 未在线（group 1 无 90s 内心跳行），先启动 samples executor 再跑", file=sys.stderr)
        sys.exit(2)
    enabled_shard = q1("SELECT COUNT(*) FROM job_info WHERE trigger_status=1 AND job_group_id=1 "
                       "AND LOWER(route_strategy)='sharding'")[0]
    if enabled_shard:
        print(f"警告：group1 有 {enabled_shard} 个启用中 sharding cron，会污染 F 口径，先停掉再跑", file=sys.stderr)
        sys.exit(2)

    hdrs = login()

    # --- 预清场：上次崩溃可能遗留「启用中」的 p1-sb-wa-* 临时任务（会污染本窗 F），先停+删 ---
    leftovers = q("SELECT id FROM job_info WHERE job_name LIKE 'p1-sb-wa-%'")
    if leftovers:
        for (lid,) in leftovers:
            try:
                requests.post(f"{BASE}/job/{lid}/stop", headers=hdrs, timeout=8)
            except Exception:
                pass
            try:
                requests.delete(f"{BASE}/job/{lid}", headers=hdrs, timeout=8)
            except Exception:
                pass
        q(f"DELETE FROM job_log WHERE job_id IN ({','.join(['%s'] * len(leftovers))})",
          tuple(l for (l,) in leftovers))
        print(f"preflight: 清理 {len(leftovers)} 个遗留 p1-sb-wa-* 临时任务")

    # --- 预检：采集窗内应只有本批临时 cron。其它启用中任务（尤其 */1 等快周期）会引入
    #      claimable 拒绝 / catch-up 追赶噪音、脏执行器失败路径，破坏「每 fire」比例口径 ---
    others = q1("SELECT COUNT(*) FROM job_info WHERE trigger_status=1 AND job_name NOT LIKE 'p1-sb-wa-%'")[0]
    if others:
        rows = q("SELECT id, job_name, cron FROM job_info WHERE trigger_status=1 "
                 "AND job_name NOT LIKE 'p1-sb-wa-%'")
        msg = "；".join(f"#{r[0]} {r[1]} cron={r[2]}" for r in rows)
        if not a.force:
            print(f"DB 有 {others} 个非本工具的启用任务，会污染采集窗，先停用或加 --force 强跑：\n  {msg}",
                  file=sys.stderr)
            sys.exit(2)
        print(f"警告(--force)：存在 {others} 个其它启用任务，窗口不再纯净，守卫断言可能 FAIL：\n  {msg}")

    jids = []
    try:
        # --- 建 N 个临时 */5 cron（SINGLE 快任务；小批量避免同边界齐射的启动堆积 churn）---
        for i in range(N):
            job = dict(jobName=f"p1-sb-wa-{i}", jobGroupId=1, jobDesc=f"stage-b writeamp cron probe {i}",
                       handlerName="demoHandler", executorParam="", cron=a.cron,
                       routeStrategy="round_robin", blockStrategy="SINGLE", retryCount=0,
                       timeout=0, triggerStatus=1)
            jid = requests.post(f"{BASE}/job", headers=hdrs, json=job, timeout=15).json()["data"]
            jids.append(jid)
        print(f"created {len(jids)} scratch cron jobs: id {jids[0]}..{jids[-1]}")

        # 静置 = 等每个临时 job 至少出 1 条 job_log：证明非静默（有真实静默窗口：整批 0 fire），
        # 且已越过创建首波瞬态（*/5 首个边界 ≤5s 到达、轮盘 fire 有 ~1.6s 延迟；创建横跨边界时
        # 拆成两组，最晚一组也 ~+7s 内出首 fire）。逐 2s 轮询，不用固定切片——fire 落在 边界+1.6s，
        # 一段 6s 切片可能正好卡在两波之间（before=8 after=8 假阴性）。
        t0 = time.time()
        while time.time() - t0 < 22:
            seen = q1("SELECT COUNT(*) FROM job_log WHERE job_id IN (%s)"
                      % ",".join(["%s"] * len(jids)), tuple(jids))[0]
            if seen >= N:
                break
            time.sleep(2)
        if seen < N:
            print(f"静置 {time.time() - t0:.0f}s 仅 {seen}/{N} 条 job_log，任务未稳定触发"
                  f"——若反复如此需排查调度器；偶发一次可重跑", file=sys.stderr)
            sys.exit(2)
        print(f"静置确认：{seen} 条 job_log（每 job ≥1 fire），已进入 {a.cron} 稳态节拍，开窗")
        # 收尾 2s：让刚出完的那波 fire 的在途 SQL 完整落账，再 TRUNCATE+开 general_log，避免掐头边沿错配
        time.sleep(2)

        # --- 采集窗：先 TRUNCATE 再开 log，窗内只含触发+周期背景 ---
        q("SET global log_output='TABLE'", db="mysql")
        q("SET global general_log=OFF", db="mysql")
        q("TRUNCATE TABLE mysql.general_log", db="mysql")
        q("SET global general_log=ON", db="mysql")
        print(f"general_log ON，观察 {W}s …", flush=True)
        time.sleep(W)
        q("SET global general_log=OFF", db="mysql")
        print("general_log OFF", flush=True)

        allrows = q("SELECT event_time, argument FROM mysql.general_log ORDER BY event_time", db="mysql")
        cnt = {}
        samples = {}
        for t, arg in allrows:
            s = clean(arg)
            for tag in classify(s):
                cnt[tag] = cnt.get(tag, 0) + 1
                if a.print_samples and tag not in samples:
                    samples[tag] = (t, s)
        # 仅保留与本次口径相关的分类做统计（其余 SQL 不进 general_log 相关表）
        keep = ["info_fu_read", "info_advance", "info_touch", "info_other_read", "info_other_update",
                "info_scan", "log_count_running", "log_insert", "log_update",
                "reg_select", "reg_upsert", "reg_clean"]
        noise_scan = cnt.get("info_scan", 0)

        F = cnt.get("log_insert", 0)
        if F == 0:
            print("窗口内 0 条 job_log INSERT——采集失败，检查任务是否触发", file=sys.stderr)
            sys.exit(2)
        fu = cnt.get("info_fu_read", 0)
        advance = cnt.get("info_advance", 0)
        touch = cnt.get("info_touch", 0)
        count_run = cnt.get("log_count_running", 0)
        log_upd = cnt.get("log_update", 0)
        reg_sel = cnt.get("reg_select", 0)
        info_other_read = cnt.get("info_other_read", 0)
        info_other_upd = cnt.get("info_other_update", 0)

        # ---- 拒绝噪声推导与剔除（见 docstring）----
        # 机制：轮盘 fire 落进边界秒（nowSec==boundary）→ claimable 拒绝（白花 1 把 claim 锁读、无 INSERT、
        # advance 不动）→ 下轮 scheduleLoop 见 next<now 做 catch-up 推进（多 1 次 advance，该边界 fire 被跳过）。
        # 故 拒绝数 = 追赶数 = advance − F；且每拒绝恰 +1 锁读 → 锁读侧推导 rejects_fu = fu − expect_fu×F 应相等。
        # 脏窗实测恒等（24==24、16==16）；把 FU/advance 各剔拒绝数后精确回归 expect_fu×F 与 F → 校正成立，
        # 「成功 fire」口径不依赖窗口干净。
        rejects = advance - F                    # 拒绝 = 被跳过的边界 fire 数
        rejects_fu = fu - expect_fu * F          # 锁读侧推导，作模型一致性交叉校验
        adj_fu = fu - rejects                    # 归属成功 fire 的锁读（剔除拒绝的裸锁读）
        adj_per = adj_fu / F
        core_raw = (fu + advance + touch + count_run + F) / F
        adj_core = (adj_fu + F + touch + count_run + F) / F   # 成功 fire 口径：advance 校正回 1/fire(=F)

        print(f"\n窗口 {W}s、F=job_log INSERT {F}（成功 fire 锚）：", flush=True)
        print(f"  job_info FOR UPDATE 读 raw {fu}（{fu/F:.3f}/fire）→ 剔拒绝 {adj_fu}（{adj_per:.3f}/fire）", flush=True)
        print(f"  推进写 {advance}（{advance/F:.3f}/fire，含 catch-up {rejects}）| ack touch {touch}"
              f"（{touch/F:.3f}）", flush=True)
        print(f"  job_log COUNT(running) {count_run}（{count_run/F:.3f}/fire）| INSERT = F"
              f" | 异步回调 UPDATE {log_upd}（{log_upd/F:.3f}/fire，边沿欠计属正常）", flush=True)
        print(f"  job_registry SELECT(route 读) {reg_sel} | 心跳 upsert {cnt.get('reg_upsert', 0)}"
              f" | cleaner DELETE {cnt.get('reg_clean', 0)}", flush=True)
        print(f"  背景剔除：scheduleLoop 预读扫描 {noise_scan} 条；其他 job_info 读 {info_other_read}"
              f" / 整行更新 {info_other_upd}", flush=True)
        print(f"  拒绝噪声：claimable 拒绝 {rejects}（{rejects/(rejects+F)*100:.0f}% 的 fire 尝试）"
              f" → 逐条剔除后成功 fire 核心 = {adj_core:.3f} /fire（raw 含噪声 {core_raw:.3f}；期望 ≈{expect_core}）",
              flush=True)
        txb = cnt.get("tx_SET autocommit=0", 0)
        txc = cnt.get("tx_commit", 0)
        print(f"  事务边界佐证：SET autocommit=0 ×{txb}、commit ×{txc}", flush=True)
        if a.print_samples:
            for tag in sorted(samples):
                t, s = samples[tag]
                print(f"    sample[{tag}] @{t.strftime('%H:%M:%S.%f')[:-3]}  {s[:200]}", flush=True)

        ok = True
        def chk(cond, msg):
            nonlocal ok
            print(("PASS  " if cond else "FAIL  ") + msg, flush=True)
            ok = ok and cond

        # ---- 硬契约（B1 决定性 / B2 中间基线）：一律用剔拒绝后的「成功 fire」口径 ----
        # 拒绝是调度器固有噪声（入轮安全量不足致边界秒内早触发，B1/B2 都改不了；根治是安全量 +2 ticks），
        # 不参与 B1 的 2tx→1tx 证据。两条推导若不一致（rejects != rejects_fu）说明存在非拒绝的异常锁读，
        # 必须 FAIL 暴露而不是被剔除逻辑吞掉。
        tol_model = max(1, int(0.03 * F))
        chk(abs(rejects - rejects_fu) <= tol_model,
            f"拒绝模型自洽（锁读侧 {rejects_fu} == 追赶侧 {rejects}，容差 {tol_model}）——无异常锁读")
        lo, hi = (0.97 * expect_fu * F, 1.03 * expect_fu * F)
        chk(lo <= adj_fu <= hi,
            f"成功 fire 的 job_info FOR UPDATE 读 ≈{expect_fu}/fire"
            f"（{'Stage A cron 双事务两把行锁' if mode=='b2' else 'B1 合并单事务单把行锁，2tx→1tx 决定性证据'}），"
            f"实际 {adj_per:.3f}")
        chk(0.9 * F <= touch <= 1.15 * F, f"ack touch 写 ≈1/fire（边沿欠计容差放宽），实际 {touch/F:.3f}")
        chk(0.9 * F <= count_run <= 1.1 * F, f"SINGLE COUNT(running) 门 ≈1/fire，实际 {count_run/F:.3f}")
        chk(reg_sel <= 2, f"route 读 job_registry ≈0（B2 缓存命中；冷启动回退会 +1），实际 {reg_sel}")
        chk(info_other_upd == 0, f"无整行 UPDATE job_info（除 advance/touch），实际 {info_other_upd}")
        exp_lo, exp_hi = (expect_core - 0.3, expect_core + 0.3)
        chk(exp_lo <= adj_core <= exp_hi,
            f"成功 fire 触发线程核心 {adj_core:.3f} /fire ∈ [{exp_lo}, {exp_hi}]"
            f"（期望 ≈{expect_core}，拒绝噪声已剔除）")
        print(f"注：拒绝率 {rejects/(rejects+F)*100:.0f}%（{rejects} 次）为调度早触发噪声、非 B1 优化对象；"
              f"回调 UPDATE {log_upd/F:.3f}/fire（异步，窗口尾欠计正常；触发线程口径不含它）", flush=True)

        if ok:
            tag = "B2-only 中间基线" if mode == "b2" else "B1+B2 GREEN 最终契约"
            print(f"\n== 写放大 cron 口径 {tag} PASS：成功 fire 每 fire ≈{adj_core:.1f} 条 DB 往返（剔拒绝），"
                  f"job_info 读={adj_per:.1f}（{'两把行锁/双事务' if mode=='b2' else '单把行锁/合并单事务'}）、"
                  f"route DB 读≈0（B2 缓存归零）==\n", flush=True)
        else:
            print("\n== cron 口径有 FAIL，见上 ==\n", flush=True)
            sys.exit(1)
    finally:
        try:
            q("SET global general_log=OFF", db="mysql")
        except Exception:
            pass
        if jids:
            ph = ",".join(["%s"] * len(jids))
            try:
                for jid in jids:
                    try:
                        requests.post(f"{BASE}/job/{jid}/stop", headers=hdrs, timeout=8)
                    except Exception:
                        pass
                for jid in jids:
                    try:
                        requests.delete(f"{BASE}/job/{jid}", headers=hdrs, timeout=8)
                    except Exception:
                        pass
                q(f"DELETE FROM job_log WHERE job_id IN ({ph})", tuple(jids))
                print(f"cleaned {len(jids)} scratch cron jobs + their logs, general_log OFF", flush=True)
            except Exception as e:
                print(f"cleanup failed: {e}", flush=True)


if __name__ == "__main__":
    main()
