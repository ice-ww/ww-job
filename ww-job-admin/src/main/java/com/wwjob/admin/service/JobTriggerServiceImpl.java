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
import java.util.List;


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
    private final JobDecisionService jobDecisionService;

    public JobTriggerServiceImpl(JobInfoMapper jobInfoMapper, JobLogMapper jobLogMapper,
                                 JobDecisionService jobDecisionService,
                                 ExecutorRouterService routerService) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobLogMapper = jobLogMapper;
        this.jobDecisionService = jobDecisionService;
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
        if ("sharding".equalsIgnoreCase(job.getRouteStrategy())) {
            broadcast(job, triggerType);
            return;
        }
        Long logId = jobDecisionService.decide(jobId, triggerType);
        if (logId == null) return;
        dispatch(logId, job, triggerType);
    }

    private void broadcast(JobInfo job, String triggerType) {
        List<String> addresses = routerService.onlineAddresses(job.getJobGroupId());
        if (addresses.isEmpty()) {
            JobLog log = new JobLog(job, triggerType, "无可用执行器", ReturnT.FAIL_CODE, JobLog.STATUS_FAIL, 0);
            jobLogMapper.insert(log);
            return;
        }
        int total = addresses.size();
        for (int i = 0; i < total; i++) {
            // handle_code = null --> 任务才受理，还未执行
            JobLog log = new JobLog(job, triggerType, "已受理，等待执行结果", null, JobLog.STATUS_RUNNING, i);
            jobLogMapper.insert(log);
            try {
                dispatchOne(log, job, addresses.get(i), total);
            } catch (Exception e) {
                log.setStatus(JobLog.STATUS_FAIL);
                log.setHandleCode(ReturnT.FAIL_CODE);
                log.setHandleMsg("投递失败：" + e.getMessage());
                log.setHandleTime(LocalDateTime.now());
                jobLogMapper.updateById(log);
            }
        }
    }

    private void dispatchOne(JobLog log, JobInfo job, String address, int shardTotal) {
        log.setExecutorAddress(address); //这里只改了内存对象，没有改数据库
        jobLogMapper.updateById(log);  // 写回数据库，否则地址白设(永远是null)
        TriggerParam param = new TriggerParam();
        param.setJobId(job.getId());
        param.setHandler(job.getHandlerName());
        param.setExecutorParam(job.getExecutorParam());
        param.setLogId(log.getId());
        param.setShardIndex(log.getShardIndex());
        param.setShardTotal(shardTotal);
        ReturnT<?> result = restTemplate.postForObject("http://" + address + "/run", param, ReturnT.class);
        if (result != null && result.getCode() == ReturnT.SUCCESS_CODE) {
            // ack 成功 = 执行器已受理，任务还在跑，日志保持 status=0 等回调
            job.setTriggerLastTime(System.currentTimeMillis());
            jobInfoMapper.updateById(job);
            return;
        }
        throw new RuntimeException(result != null ? result.getMsg() : "无返回");

    }


    /** 路由 + 分发 + 最多 retryCount 次重试，最后按"成功 / 明确失败 / 超时未知"三态落日志 */
    private void dispatch(Long logId, JobInfo job, String triggerType) {
        long jobId = job.getId();
        JobLog log = jobLogMapper.selectById(logId);
        if (log == null) return;
        int retryCount = job.getRetryCount() == null ? 0 : job.getRetryCount();
        Exception lastError = null;

        // 最多尝试 retryCount+1 次：第 1 次 + retryCount 次重试
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            String address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), jobId);
            if (address == null) {
                log.setStatus(JobLog.STATUS_FAIL);
                log.setHandleMsg("无可用执行器");
                jobLogMapper.updateById(log);
                return;
            }
            try {
                dispatchOne(log, job, address, 0); //单台传 shardTotal=0
                    return;
            } catch (Exception e) {
                lastError = e;
                // 超时 = 结果未知，执行器可能仍在执行：重试必然造成重复执行（扣款/发短信等非幂等事故），直接放弃。
                // 只有连接被拒绝（handler 没跑）或明确失败响应才允许重试
                if (isTimeout(e)) {
                    break;
                }
            }
        }

        // 三态落日志：成功 / 明确失败 / 超时未知
        // 成功在上面已处理，只剩失败和超时未知
        if (isTimeout(lastError)) {
            log.setStatus(JobLog.STATUS_UNKNOWN);
            log.setHandleCode(ReturnT.FAIL_CODE);
            log.setHandleMsg("执行超时，结果未知：执行器可能仍在执行，请以执行器日志为准，勿重复触发");
        } else {
            log.setStatus(JobLog.STATUS_FAIL);
            log.setHandleCode(ReturnT.FAIL_CODE);
            log.setHandleMsg(lastError != null ? lastError.getMessage() : "无返回");
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

}
