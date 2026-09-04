#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
停用 group 下指定 id 区间的任务（压测 drain 用，与 enable.py 对称）。
用法：python disable.py 1 3000 --group 1
"""
import argparse
import sys

import requests

BASE = "http://localhost:8080"


def login():
    r = requests.post(f"{BASE}/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("id_min", type=int)
    p.add_argument("id_max", type=int)
    p.add_argument("--group", type=int, default=1)
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    headers = login()
    n = 0
    for job_id in range(args.id_min, args.id_max + 1):
        if args.dry_run:
            n += 1
            continue
        r = requests.post(f"{BASE}/job/{job_id}/stop", headers=headers, timeout=10)
        if r.status_code != 200 or r.json().get("code") != 200:
            print(f"fail at id={job_id}: HTTP {r.status_code} {r.text}", flush=True)
            sys.exit(1)
        n += 1
        if n % 100 == 0:
            print(f"  stopped {n}...", flush=True)
    print(f"stopped {n} jobs id[{args.id_min}..{args.id_max}]", flush=True)


if __name__ == "__main__":
    main()
