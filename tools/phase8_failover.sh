#!/usr/bin/env bash
# Phase 8 故障恢复：饱和批中途 kill admin A，验证 executor 侧 failover + 巡检兜底。
# 流程：enable 1-3000 → warmup 90s → 每 10s 采样；i==6 时 taskkill adminA → 续采样到 i=45。
# 日志：tools/logs/phase8_failover.log
# 用法：bash tools/phase8_failover.sh <adminA_pid> <adminB_pid>
MYSQL="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
LOG=/d/javacode/ww-job/tools/logs/phase8_failover.log
ADM_A=${1:?adminA pid}
ADM_B=${2:?adminB pid}
: > "$LOG"
echo "PHASE8_START $(date '+%H:%M:%S') adminA_pid=$ADM_A adminB_pid=$ADM_B" >> "$LOG"

# 1) enable 批次（打到 A:8080）
python /d/javacode/ww-job/tools/enable.py 1 3000 >> "$LOG" 2>&1
echo "ENABLED $(date '+%H:%M:%S')" >> "$LOG"

# 2) warmup 90s（双 admin 共同调度，进入饱和稳态）
echo "WARMUP 90s" >> "$LOG"
sleep 90
echo "WARMUP_DONE $(date '+%H:%M:%S')" >> "$LOG"

# 3) 每 10s 采样；i==6 时 kill A
DBQ() { "$MYSQL" -h127.0.0.1 -P3307 -uroot -proot ww_job_loadtest -N -e "$1" 2>/dev/null; }
for i in $(seq 0 45); do
  TS=$(date '+%H:%M:%S')
  if [ "$i" = "6" ]; then
    echo ">>> KILLING adminA pid=$ADM_A $(date '+%H:%M:%S')" >> "$LOG"
    taskkill //F //PID "$ADM_A" >> "$LOG" 2>&1
  fi
  DB=$(DBQ "SELECT CONCAT((SELECT COUNT(*) FROM job_log),' ',(SELECT COUNT(*) FROM job_log WHERE trigger_time>=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)-INTERVAL 10 SECOND),' ',(SELECT COUNT(*) FROM job_log WHERE status=3 AND trigger_time>=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)-INTERVAL 60 SECOND),' ',(SELECT COUNT(*) FROM job_log WHERE status=0 AND trigger_time>=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)-INTERVAL 60 SECOND),' ',(SELECT COUNT(DISTINCT job_id) FROM job_log WHERE trigger_time>=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)-INTERVAL 60 SECOND));")
  AA=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:8080/registry/list 2>/dev/null)
  AB=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:8082/registry/list 2>/dev/null)
  # cols: total | last10s | st3_60s | running0_60s | distinct60 | A_http B_http
  echo "$TS $DB | A=$AA B=$AB" >> "$LOG"
  if [ $i -lt 45 ]; then sleep 10; fi
done
echo "PHASE8_DONE $(date '+%H:%M:%S')" >> "$LOG"
