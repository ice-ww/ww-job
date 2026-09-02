#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重新启用 group 下指定 id 区间的任务（用于恢复被误停的批次）。
用法：python enable.py 132 631 --group 1
"""
import argparse
import sys
import time

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
        r = requests.post(f"{BASE}/job/{job_id}/start", headers=headers, timeout=10)
        if r.status_code != 200 or r.json().get("code") != 200:
            print(f"fail at id={job_id}: HTTP {r.status_code} {r.text}", flush=True)
            sys.exit(1)
        n += 1
        if n % 100 == 0:
            print(f"  enabled {n}...", flush=True)
    print(f"enabled {n} jobs id[{args.id_min}..{args.id_max}]", flush=True)


if __name__ == "__main__":
    main()
