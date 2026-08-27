package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.ReturnT;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author 王威
 * @version 1.0
 */

/**
 * 超时巡检：执行器宕机导致回调永不来的兜底。
 * 把 status=0 且超过阈值的日志标记为 status=3（结果未知）；阈值 = 任务 timeout，0 则默认 60s。
 * status 离开 0 的同时，DB 互斥位（status=0 计数）自然释放，调度不会被死执行器堵死。
 */
@Component
public class JobLogTimeoutScanner {
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final JobLogMapper jobLogMapper;
    private final JobInfoMapper jobInfoMapper;

    public JobLogTimeoutScanner(JobLogMapper jobLogMapper, JobInfoMapper jobInfoMapper) {
        this.jobLogMapper = jobLogMapper;
        this.jobInfoMapper = jobInfoMapper;
    }

    @Scheduled(fixedDelay = 30000)
    public void scan() {
        List<JobLog> runningLogs = jobLogMapper.selectList(new QueryWrapper<JobLog>()
                .eq("status", JobLog.STATUS_RUNNING));
        for (JobLog log : runningLogs) {
            JobInfo job = jobInfoMapper.selectById(log.getJobId());
            long timeoutSec = (job != null && job.getTimeout() != null && job.getTimeout() > 0)
                    ? job.getTimeout() : DEFAULT_TIMEOUT_SECONDS;
            LocalDateTime deadline = log.getTriggerTime().plusSeconds(timeoutSec);
            if (LocalDateTime.now().isAfter(deadline)) {
                log.setStatus(JobLog.STATUS_UNKNOWN);
                log.setHandleCode(ReturnT.FAIL_CODE);
                log.setHandleMsg("执行超时未收到回调，结果未知");
                log.setHandleTime(LocalDateTime.now());
                jobLogMapper.updateById(log);
            }
        }
    }
}
