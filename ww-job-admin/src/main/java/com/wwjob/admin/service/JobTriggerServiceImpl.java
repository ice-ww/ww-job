package com.wwjob.admin.service;

import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 王威
 * @version 1.0
 */
@Service
public class JobTriggerServiceImpl implements JobTriggerService {
    private final JobInfoMapper jobInfoMapper;
    private final JobLogMapper jobLogMapper;
    private final ExecutorRouterService routerService;
    private final RestTemplate restTemplate;
    /** block_strategy=SINGLE 时的执行中任务集合：同一任务同一时刻只允许一个实例在跑 */
    private final Set<Long> runningJobIds = ConcurrentHashMap.newKeySet();

    public JobTriggerServiceImpl(JobInfoMapper jobInfoMapper, JobLogMapper jobLogMapper,
                                 ExecutorRouterService routerService) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobLogMapper = jobLogMapper;
        this.routerService = routerService;
        // 连接 3s / 读 10s 超时：执行器挂起时不会永久阻塞触发线程（默认 RestTemplate 超时是无限）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public void trigger(long jobId, String triggerType) {
        JobInfo job = jobInfoMapper.selectById(jobId);
        if (job == null) return;

        boolean single = "SINGLE".equalsIgnoreCase(job.getBlockStrategy());
        if (single) {
            if (!runningJobIds.add(jobId)) {
                // 上一次执行尚未结束：丢弃本次触发（记"被阻塞"日志，不制造重复执行）
                saveLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃", null, JobLog.STATUS_UNKNOWN);
                return;
            }
        }
        try {
            dispatchWithRetry(job, triggerType);
        } finally {
            if (single) runningJobIds.remove(jobId);
        }
    }

    /** 路由 + 分发 + 最多 retryCount 次重试，最后按"成功 / 明确失败 / 超时未知"三态落日志 */
    private void dispatchWithRetry(JobInfo job, String triggerType) {
        long jobId = job.getId();
        int retryCount = job.getRetryCount() == null ? 0 : job.getRetryCount();
        ReturnT<?> result = null;
        Exception lastError = null;
        JobLog log = null;

        // 最多尝试 retryCount+1 次：第 1 次 + retryCount 次重试
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            String address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), jobId);
            if (address == null) {
                log = saveLog(job, triggerType, "无可用执行器", null, JobLog.STATUS_FAIL);
                return;
            }
            // 首次调用建日志；重试复用同一条日志并刷新执行地址（可能换了台执行器）
            if (log == null) {
                log = saveLog(job, triggerType, null, address, JobLog.STATUS_RUNNING);
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
                // 超时 = 结果未知，执行器可能仍在执行：重试必然造成重复执行（扣款/发短信等非幂等事故），直接放弃。
                // 只有连接被拒绝（handler 没跑）或明确失败响应才允许重试
                if (isTimeout(e)) {
                    break;
                }
            }
        }

        // 三态落日志：成功 / 明确失败 / 超时未知，不再把"结果未知"误判成"失败"
        if (result != null && result.getCode() == ReturnT.SUCCESS_CODE) {
            log.setStatus(JobLog.STATUS_SUCCESS);
            log.setHandleCode(ReturnT.SUCCESS_CODE);
        } else if (isTimeout(lastError)) {
            log.setStatus(JobLog.STATUS_UNKNOWN);
            log.setHandleCode(ReturnT.FAIL_CODE);
            log.setHandleMsg("执行超时，结果未知：执行器可能仍在执行，请以执行器日志为准，勿重复触发");
        } else {
            log.setStatus(JobLog.STATUS_FAIL);
            log.setHandleCode(ReturnT.FAIL_CODE);
            log.setHandleMsg(lastError != null ? lastError.getMessage()
                    : (result == null ? "无返回" : result.getMsg()));
        }
        log.setHandleTime(LocalDateTime.now());
        jobLogMapper.updateById(log);
        job.setTriggerLastTime(System.currentTimeMillis());
        jobInfoMapper.updateById(job);
    }

    /** RestTemplate 把 SocketTimeoutException 包在 ResourceAccessException 里，沿 cause 链查找 */
    private boolean isTimeout(Exception e) {
        if (e == null) return false;
        Throwable c = e;
        while (c != null) {
            if (c instanceof SocketTimeoutException) return true;
            c = c.getCause();
        }
        return false;
    }

    private JobLog saveLog(JobInfo job, String triggerType, String failMsg, String address, int status) {
        JobLog log = new JobLog();
        log.setJobId(job.getId());
        log.setJobGroupId(job.getJobGroupId());
        log.setExecutorAddress(address);
        log.setHandlerName(job.getHandlerName());
        log.setTriggerType(triggerType);
        log.setTriggerTime(LocalDateTime.now());
        log.setStatus(status);
        log.setHandleMsg(failMsg);
        jobLogMapper.insert(log);
        return log;
    }
}
