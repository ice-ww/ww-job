#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Deterministic repro for F2-9: a concurrent /stop is overwritten ("revived") by the
in-flight trigger's whole-entity updateById of a stale JobInfo snapshot.

Mechanism: trigger() reads the job once (status=1). dispatchOne POSTs /run to the
executor and blocks up to the read timeout (10s). If /stop commits (status=0) inside
that window, the timeout/failure tail of dispatch() does updateById(stale job) which
writes triggerStatus back to 1 -> the task is revived.

Determinism: route the job to a "stall" address that accepts TCP but never responds,
stretching the revival window from milliseconds to ~10s. Single-shot verdict.

Usage:
  python repro_f29.py --base http://localhost:8080 --port 9099
Before fix: expect REVIVED (bug, exit 1). After fix: expect stopped (exit 0).
"""
import argparse
import socket
import socketserver
import threading
import time

import requests


def login(base):
    r = requests.post(f"{base}/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=10)
    return {"Authorization": "Bearer " + r.json()["data"]["token"]}


def http(base, method, path, headers=None, json_body=None, timeout=15):
    r = requests.request(method, f"{base}{path}", headers=headers, json=json_body, timeout=timeout)
    if r.status_code != 200:
        raise RuntimeError(f"{method} {path} -> HTTP {r.status_code}: {r.text[:200]}")
    return r.json()


def job_status(base, headers, job_id):
    """Read trigger_status via GET /job/page (desc by id, newest on page 1)."""
    for page in range(1, 6):
        data = http(base, "GET", f"/job/page?page={page}&size=100", headers=headers)
        recs = (data.get("data") or {}).get("records") or data.get("records") or []
        for rec in recs:
            if rec.get("id") == job_id:
                return rec.get("triggerStatus")
        if len(recs) < 100:
            break
    return None


class _StallHandler(socketserver.BaseRequestHandler):
    """Read the request, then never respond until the client times out and disconnects."""

    def handle(self):
        try:
            self.request.settimeout(0.5)
            while True:
                try:
                    data = self.request.recv(4096)
                    if not data:
                        break
                except socket.timeout:
                    pass
                except OSError:
                    break
                time.sleep(0.2)
        except Exception:
            pass
        finally:
            try:
                self.request.close()
            except Exception:
                pass


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default="http://localhost:8080")
    p.add_argument("--port", type=int, default=9099, help="stall address port")
    p.add_argument("--wait-stop", type=float, default=1.2, help="seconds after trigger before /stop")
    args = p.parse_args()

    suffix = int(time.time() * 1000) % 100000
    group_name = f"f29-stall-{suffix}"
    stall_addr = f"http://127.0.0.1:{args.port}"

    # 1) stall TCP server: accept but never respond -> forces the 10s read timeout
    srv = socketserver.ThreadingTCPServer(("127.0.0.1", args.port), _StallHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    headers = login(args.base)
    print(f"[1] stall server  @ {stall_addr}", flush=True)

    # 2) isolated job_group (does not touch the real sample-executor group)
    g = http(args.base, "POST", "/jobgroup",
             headers=headers, json_body={"appName": group_name, "title": "f29 stall", "addressType": 0})
    group_id = (g.get("data") or {}).get("id") if isinstance(g.get("data"), dict) else g.get("data")
    print(f"[2] job_group {group_name} id={group_id}", flush=True)

    # 3) register the stall address under that group (fresh heartbeat -> route picks it)
    reg = http(args.base, "POST", "/registry", headers=headers,
               json_body={"registryKey": group_name, "registryValue": stall_addr})
    print(f"[3] registry {reg.get('code')} {group_name} -> {stall_addr}", flush=True)

    # 4) create job (handler name arbitrary - request never reaches a real executor);
    #    yearly cron so the scheduler never fires during the ~15s test
    body = {
        "jobGroupId": group_id, "jobName": "f29", "jobDesc": "f29 repro",
        "handlerName": "loadTestHandler", "executorParam": "", "cron": "0 0 0 1 1 ?",
        "routeStrategy": "round", "blockStrategy": "SINGLE", "retryCount": 0,
    }
    cj = http(args.base, "POST", "/job", headers=headers, json_body=body)
    job_id = (cj.get("data") or {}).get("id") if isinstance(cj.get("data"), dict) else cj.get("data")
    print(f"[4] job id={job_id} (yearly cron to keep scheduler quiet)", flush=True)

    # 5) start -> status=1 so the trigger reads a status=1 snapshot
    http(args.base, "POST", f"/job/{job_id}/start", headers=headers)
    print(f"[5] start -> trigger_status={job_status(args.base, headers, job_id)}", flush=True)

    # 6) manual trigger on a background thread: dispatchOne blocks on stall read (up to 10s)
    trig = threading.Thread(target=lambda: http(args.base, "POST", f"/job/{job_id}/trigger",
                                                headers=headers, timeout=20))
    trig.start()
    print("[6] manual trigger fired (in-flight, blocked on stall read)", flush=True)

    # 7) call /stop inside the read-timeout window
    time.sleep(args.wait_stop)
    http(args.base, "POST", f"/job/{job_id}/stop", headers=headers)
    st_at_stop = job_status(args.base, headers, job_id)
    print(f"[7] stop @ t={args.wait_stop}s -> trigger_status={st_at_stop} (expect 0)", flush=True)

    # 8) wait for dispatch tail (10s read timeout + margin), then re-check
    trig.join(timeout=25)
    time.sleep(2)
    final = job_status(args.base, headers, job_id)
    print(f"[8] after dispatch tail trigger_status={final}", flush=True)

    print()
    if final == 0:
        print("==> stopped: stop survived, F2-9 NOT reproduced (FIXED)", flush=True)
        return 0
    if final == 1:
        print("==> REVIVED: stop overwritten by stale whole-entity write, F2-9 reproduced (BUG)", flush=True)
        return 1
    print(f"==> inconclusive (status={final})", flush=True)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
