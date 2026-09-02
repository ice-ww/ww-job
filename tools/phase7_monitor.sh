#!/usr/bin/env bash
# Phase 7 稳定性 30 分钟监控：每 3 分钟采样密度/status3/GC/堆占用/线程数/MySQL 连接数。
# 用法：bash tools/phase7_monitor.sh <adminA_pid> <adminB_pid>  （后台跑）
MYSQL="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
JSTAT=/d/Program/wwjdk21/bin/jstat.exe
JCMD=/d/Program/wwjdk21/bin/jcmd.exe
LOG=/d/javacode/ww-job/tools/logs/phase7_monitor.log
ADM_A=${1:?adminA pid}
ADM_B=${2:?adminB pid}
SAMPLES=11
INTERVAL=180
: > "$LOG"
echo "# ts total last10s st3_60s distinct60 | A[O% YGC FGC] | B[O% YGC FGC] | A_thr B_thr | mysql_conns" >> "$LOG"
for i in $(seq 0 $((SAMPLES-1))); do
  TS=$(date '+%H:%M:%S')
  DB=$("$MYSQL" -h127.0.0.1 -P3307 -uroot -proot ww_job_loadtest -N -e \
    "SELECT CONCAT((SELECT COUNT(*) FROM job_log),' ',(SELECT COUNT(*) FROM job_log WHERE trigger_time>=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)-INTERVAL 10 SECOND),' ',(SELECT COUNT(*) FROM job_log WHERE status=3 AND trigger_time>=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)-INTERVAL 60 SECOND),' ',(SELECT COUNT(DISTINCT job_id) FROM job_log WHERE trigger_time>=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)-INTERVAL 60 SECOND));" 2>/dev/null)
  A=$("$JSTAT" -gcutil "$ADM_A" 2>/dev/null | tail -1 | awk '{print $4"%", $7, $9}')
  B=$("$JSTAT" -gcutil "$ADM_B" 2>/dev/null | tail -1 | awk '{print $4"%", $7, $9}')
  AT=$("$JCMD" "$ADM_A" Thread.print 2>/dev/null | grep -c '^"')
  BT=$("$JCMD" "$ADM_B" Thread.print 2>/dev/null | grep -c '^"')
  MC=$("$MYSQL" -h127.0.0.1 -P3307 -uroot -proot -N -e "SELECT COUNT(*) FROM information_schema.processlist;" 2>/dev/null)
  echo "$TS $DB | A[$A] | B[$B] | ${AT:-?} ${BT:-?} | $MC" >> "$LOG"
  if [ $i -lt $((SAMPLES-1)) ]; then sleep $INTERVAL; fi
done
echo "MONITOR_DONE" >> "$LOG"
