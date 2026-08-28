package com.wwjob.admin.alarm;

import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author 王威
 * @version 1.0
 */

@Component
public class JobFailMonitor {
    private static final Logger log = LoggerFactory.getLogger(JobFailMonitor.class);
    private static final int WINDOW_MINUTES = 10;
    private final ConcurrentHashMap<Long, Long> lastAlertAt = new ConcurrentHashMap<>();

    private final JobLogMapper jobLogMapper;
    private final JobInfoMapper jobInfoMapper;
    private final AlarmHandler alarmHandler;

    public JobFailMonitor(JobLogMapper jobLogMapper, JobInfoMapper jobInfoMapper, AlarmHandler alarmHandler) {
        this.jobLogMapper = jobLogMapper;
        this.jobInfoMapper = jobInfoMapper;
        this.alarmHandler = alarmHandler;
    }

    @Scheduled(fixedRate = 30000)
    public void scan() {
        try {
            LocalDateTime from = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
            List<JobLog> failed = jobLogMapper.selectRecentlyFailed(from);
            Map<Long, List<JobLog>> byJob = failed.stream()
                    .collect(Collectors.groupingBy(JobLog::getJobId));
            long now = System.currentTimeMillis();
            for (Map.Entry<Long, List<JobLog>> entry : byJob.entrySet()) {
                long jobId = entry.getKey();
                List<JobLog> logs = entry.getValue();
                if (now - lastAlertAt.getOrDefault(jobId, 0L) < WINDOW_MINUTES * 60_000) {
                    continue;
                }
                JobInfo job = jobInfoMapper.selectById(jobId);
                if (job == null || job.getAlarmConfig() == null || job.getAlarmConfig().isBlank()) {
                    // 未订阅，跳过
                    continue;
                }
                String title = "【ww-job 告警】 任务" + job.getJobName() + "执行失败";
                alarmHandler.send(job.getAlarmConfig(), title, buildContent(job, logs));
                lastAlertAt.put(jobId, now);  // 只有发送成功才记录（失败下次重试）
            }
        } catch (Exception e) {
            log.error("失败告警扫描异常", e);
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
