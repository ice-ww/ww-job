import requests, time, sys, json
from datetime import datetime

BASE = "http://localhost:8080"
r = requests.post(f"{BASE}/auth/login", json={"username":"admin","password":"admin123"}, timeout=10)
token = r.json()["data"]["token"]
H = {"Authorization": f"Bearer {token}"}

# 连续两次 DB 查询之间的 job_log 增量 = 密度
def density_sql():
    # 用 API: 查 joblog 总数统计——但没有直接 count 接口，改用 status 过滤分页 total
    # 这里用 job_log 表直接算更准，但 API 只有 page。用总页 total 累加计数
    return None

# 方案：每 10s 查一次 joblog/page 拿 total，与上次差值 /10 = 密度
def get_total():
    r = requests.get(f"{BASE}/joblog/page", params={"current":1,"size":1}, headers=H, timeout=10)
    return r.json().get("total", 0)

prev = get_total()
print(f"t0 total_joblog={prev} {datetime.now().strftime('%H:%M:%S')}", flush=True)
for i in range(18):  # 180s / 10s = 18 个点
    time.sleep(10)
    now = get_total()
    delta = now - prev
    density = delta / 10.0
    print(f"t={i+1:2d} {datetime.now().strftime('%H:%M:%S')} total={now} delta={delta:5d} density~{density:.1f}/s", flush=True)
    prev = now
