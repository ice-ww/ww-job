package com.wwjob.admin.alarm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobAlertState;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobAlertStateMapper;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.admin.service.JobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author 王威
 * @version 1.0
 */

@Component
public class JobFailMonitor {
    private static final Logger log = LoggerFactory.getLogger(JobFailMonitor.class);
    private static final int WINDOW_MINUTES = 10;

    private final JobLogMapper jobLogMapper;
    private final JobInfoMapper jobInfoMapper;
    private final JobAlertStateMapper alertStateMapper;
    private final AlarmHandler alarmHandler;
    private final JobLockService jobLockService;

    public JobFailMonitor(JobLogMapper jobLogMapper, JobInfoMapper jobInfoMapper,
                          JobAlertStateMapper alertStateMapper, AlarmHandler alarmHandler,
                          JobLockService jobLockService) {
        this.jobLogMapper = jobLogMapper;
        this.jobInfoMapper = jobInfoMapper;
        this.alertStateMapper = alertStateMapper;
        this.alarmHandler = alarmHandler;
        this.jobLockService = jobLockService;
    }

    @Scheduled(fixedRate = 30000)
    public void scan() {
        try {
            // 全局锁：同一时刻只有一台 admin 在扫告警；去重状态在共享 DB，多 admin 不重复发
            jobLockService.withLock("alert_lock", this::scanLocked);
        } catch (Exception e) {
            log.error("失败告警扫描异常", e);
        }
    }

    private void scanLocked() {
        LocalDateTime from = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
        List<JobLog> failed = jobLogMapper.selectRecentlyFailed(from);
        Map<Long, List<JobLog>> byJob = failed.stream()
                .collect(Collectors.groupingBy(JobLog::getJobId));
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, List<JobLog>> entry : byJob.entrySet()) {
            long jobId = entry.getKey();
            List<JobLog> logs = entry.getValue();
            JobAlertState st = alertStateMapper.selectOne(new QueryWrapper<JobAlertState>().eq("job_id", jobId));
            if (st != null && now - st.getLastAlertAt() < WINDOW_MINUTES * 60_000) {
                continue;  // DB 去重窗口：上次告警 10min 内跳过
            }
            JobInfo job = jobInfoMapper.selectById(jobId);
            if (job == null || job.getAlarmConfig() == null || job.getAlarmConfig().isBlank()) {
                continue;  // 未订阅告警
            }
            String title = "【ww-job 告警】 任务" + job.getJobName() + "执行失败";
            try {
                alarmHandler.send(job.getAlarmConfig(), title, buildContent(job, logs));
            } catch (Exception e) {
                // 发送失败：整批回滚（不 upsert），下次扫描重试。至少一次语义，宁可重复不丢
                throw new RuntimeException("告警发送失败 jobId=" + jobId, e);
            }
            upsertAlertState(jobId, now);  // 发送成功才记 last_alert_at
        }
    }

    /** 发送成功后落库去重时间戳（存在则更新，不存在则插入） */
    private void upsertAlertState(long jobId, long now) {
        JobAlertState st = alertStateMapper.selectOne(new QueryWrapper<JobAlertState>().eq("job_id", jobId));
        if (st == null) {
            st = new JobAlertState();
            st.setJobId(jobId);
            st.setLastAlertAt(now);
            alertStateMapper.insert(st);
        } else {
            st.setLastAlertAt(now);
            alertStateMapper.updateById(st);
        }
    }

    private String buildContent(JobInfo job, List<JobLog> logs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            JobLog log = logs.get(i);
            if (logs.size() > 1) sb.append("— 日志 ").append(i + 1).append("/").append(logs.size()).append(" —\n");
            sb.append("【ww-job 任务告警】\n");
            sb.append("任务：").append(job.getJobName()).append("（jobId=").append(job.getId()).append("）\n");
            sb.append("执行器：").append(log.getExecutorAddress()).append("\n");
            sb.append("触发方式：").append(log.getTriggerType()).append("\n");
            sb.append("失败时间：").append(log.getHandleTime()).append("\n");
            sb.append("状态：").append(log.getStatus() == JobLog.STATUS_FAIL ? "执行失败" : "超时，结果未知").append("\n");
            sb.append("失败原因：").append(log.getHandleMsg()).append("\n");
            sb.append("日志ID：").append(log.getId()).append("\n");
            if (i < logs.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }


}
