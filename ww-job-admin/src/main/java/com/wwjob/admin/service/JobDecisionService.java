package com.wwjob.admin.service;

import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;


/**
 * @author 王威
 * @version 1.0
 */

@Service
public class JobDecisionService {
    private final JobInfoMapper jobInfoMapper;
    private final JobLogMapper jobLogMapper;


    public JobDecisionService(JobInfoMapper jobInfoMapper, JobLogMapper jobLogMapper) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobLogMapper = jobLogMapper;
    }

    @Transactional
    public Long decide(long jobId, String triggerType) {
        JobInfo job =  jobInfoMapper.selectByIdForUpdate(jobId);
        if (job == null) {
            return null;
        }
        boolean single = "SINGLE".equalsIgnoreCase(job.getBlockStrategy());
        if (single) {
            long running = jobLogMapper.countRunning(jobId);
            if (running > 0) {
                // 上一次执行尚未结束：丢弃本次触发（记"被阻塞"日志，不制造重复执行）
                saveLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃", JobLog.STATUS_UNKNOWN);
                return null;
            }
        }
        JobLog log = saveLog(job, triggerType, "已受理，等待执行结果", JobLog.STATUS_RUNNING);
        return log.getId();
    }

    private JobLog saveLog(JobInfo job, String triggerType, String handleMsg, int status) {
        JobLog log = new JobLog();
        log.setJobId(job.getId());
        log.setJobGroupId(job.getJobGroupId());
        log.setHandlerName(job.getHandlerName());
        log.setTriggerType(triggerType);
        log.setTriggerTime(LocalDateTime.now());
        log.setStatus(status);
        log.setHandleMsg(handleMsg);
        jobLogMapper.insert(log);
        return log;
    }
}
