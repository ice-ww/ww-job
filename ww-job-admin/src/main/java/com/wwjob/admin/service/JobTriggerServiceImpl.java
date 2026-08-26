package com.wwjob.admin.service;

import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@Service
public class JobTriggerServiceImpl implements JobTriggerService {
    private final JobInfoMapper jobInfoMapper;
    private final JobLogMapper jobLogMapper;
    private final ExecutorRouterService routerService;
    private final RestTemplate restTemplate = new RestTemplate();

    public JobTriggerServiceImpl(JobInfoMapper jobInfoMapper, JobLogMapper jobLogMapper,
                                 ExecutorRouterService routerService) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobLogMapper = jobLogMapper;
        this.routerService = routerService;
    }

    @Override
    public void trigger(long jobId, String triggerType) {
        JobInfo job = jobInfoMapper.selectById(jobId);
        if (job == null) return;
        // 判空兜底
        int retryCount = job.getRetryCount() == null ? 0 : job.getRetryCount();
        ReturnT<?> result = null;
        Exception lastError = null;
        JobLog log = null;

        // 最多尝试 retryCount+1 次：第 1 次 + retryCount 次重试
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            String address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), jobId);
            if (address == null) {
                log = saveLog(job, "无可用执行器", null, 2);
                return;
            }
            // 首次调用建日志；重试复用同一条日志并刷新执行地址（可能换了台执行器）
            if (log == null) {
                log = saveLog(job, null, address, 0);
            } else {
                log.setExecutorAddress(address);
            }
            TriggerParam param = new TriggerParam();
            param.setJobId(jobId);
            param.setHandler(job.getHandlerName());
            param.setExecutorParam(job.getExecutorParam());
            param.setLogId(log.getId());
            try {
                result = restTemplate.postForObject("http://" + address + "/run", param, ReturnT.class);
                if (result != null && result.getCode() == ReturnT.SUCCESS_CODE) {
                    break;  // 成功，不再重试
                }
            } catch (Exception e) {
                lastError = e;
            }
        }

        // 按最终结果落日志
        if (result != null && result.getCode() == ReturnT.SUCCESS_CODE) {
            log.setStatus(1); log.setHandleCode(ReturnT.SUCCESS_CODE);
        } else {
            log.setStatus(2); log.setHandleCode(ReturnT.FAIL_CODE);
            log.setHandleMsg(lastError != null ? lastError.getMessage()
                    : (result == null ? "无返回" : result.getMsg()));
        }
        log.setHandleTime(LocalDateTime.now());
        jobLogMapper.updateById(log);
        job.setTriggerLastTime(System.currentTimeMillis());
        jobInfoMapper.updateById(job);
    }

    private JobLog saveLog(JobInfo job, String failMsg, String address, int status) {
        JobLog log = new JobLog();
        log.setJobId(job.getId());
        log.setJobGroupId(job.getJobGroupId());
        log.setExecutorAddress(address);
        log.setHandlerName(job.getHandlerName());
        log.setTriggerTime(LocalDateTime.now());
        log.setStatus(status);
        log.setHandleMsg(failMsg);
        jobLogMapper.insert(log);
        return log;
    }
}
