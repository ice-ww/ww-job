#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 9 工具：对 id 区间逐个 POST /job/{id}/trigger（顺序、带鉴权），用于
executor-kill 故障恢复场景的确定性手动触发。

用法：
  python phase9_trigger.py 11 40 --label "windowA executor-down"
可选 --base 指向 admin（默认 8080 = admin A）。
响应恒 code=200（trigger 同步落账，是否成功以 job_log 为准），打印触发数与耗时。
"""
import argparse
import time

import requests


def login(base):
    r = requests.post(f"{base}/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("id_min", type=int)
    p.add_argument("id_max", type=int)
    p.add_argument("--base", default="http://localhost:8080")
    p.add_argument("--label", default="")
    args = p.parse_args()

    headers = login(args.base)
    n = args.id_max - args.id_min + 1
    t0 = time.time()
    ok = 0
    for jid in range(args.id_min, args.id_max + 1):
        try:
            r = requests.post(f"{args.base}/job/{jid}/trigger", headers=headers, timeout=30)
            if r.json().get("code") == 200:
                ok += 1
            else:
                print(f"{jid} code={r.json().get('code')} msg={r.json().get('msg')}", flush=True)
        except Exception as e:
            print(f"{jid} EXC {e}", flush=True)
    label = args.label or f"trigger {args.id_min}-{args.id_max}"
    print(f"{label}: fired={n} ok={ok} wall={time.time() - t0:.1f}s", flush=True)


if __name__ == "__main__":
    main()
