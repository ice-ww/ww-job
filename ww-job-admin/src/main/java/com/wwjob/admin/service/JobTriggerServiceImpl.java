package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
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
    private final RestTemplate restTemplate;
    private final TransactionTemplate transactionTemplate;

    public JobTriggerServiceImpl(JobInfoMapper jobInfoMapper, JobLogMapper jobLogMapper,
                                 ExecutorRouterService routerService,
                                 PlatformTransactionManager transactionManager) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobLogMapper = jobLogMapper;
        this.routerService = routerService;
        // /run 只等 ack（瞬时）：connect 3s / read 5s
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void trigger(long jobId, String triggerType) {
        JobInfo job = jobInfoMapper.selectById(jobId);
        if (job == null) return;

        boolean single = "SINGLE".equalsIgnoreCase(job.getBlockStrategy());
        // 决策：行锁 + DB status=0 计数判断互斥。锁内不发 HTTP。
        JobLog log = transactionTemplate.execute(status -> decide(job, single, triggerType));
        if (log == null) return;  // 被阻塞，已插 status=3 日志

        // 锁外投递：retryCount 只作用于投递阶段
        dispatch(log, job);
    }

    /** 事务决策。返回 null 表示被阻塞；否则返回新插入的 status=0 日志（= 互斥位） */
    private JobLog decide(JobInfo job, boolean single, String triggerType) {
        jobInfoMapper.selectByIdForUpdate(job.getId());  // 行锁串行化同一任务
        long running = jobLogMapper.selectCount(new QueryWrapper<JobLog>()
                .eq("job_id", job.getId())
                .eq("status", JobLog.STATUS_RUNNING));
        if (single && running > 0) {
            saveLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃", null, JobLog.STATUS_UNKNOWN);
            return null;
        }
        return saveLog(job, triggerType, null, null, JobLog.STATUS_RUNNING);
    }

    /** 投递 /run 等 ack。ack 成功即完成投递，执行结果等执行器回调 */
    private void dispatch(JobLog log, JobInfo job) {
        int retryCount = job.getRetryCount() == null ? 0 : job.getRetryCount();
        ReturnT<?> lastResult = null;
        Exception lastError = null;
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            String address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), job.getId());
            if (address == null) {
                markFailed(log, "无可用执行器");
                return;
            }
            log.setExecutorAddress(address);
            TriggerParam param = new TriggerParam();
            param.setJobId(job.getId());
            param.setHandler(job.getHandlerName());
            param.setExecutorParam(job.getExecutorParam());
            param.setLogId(log.getId());
            try {
                lastResult = restTemplate.postForObject("http://" + address + "/run", param, ReturnT.class);
                if (lastResult != null && lastResult.getCode() == ReturnT.SUCCESS_CODE) {
                    // ack 收到：投递成功，记录执行地址，结果等回调。
                    // 定点更新 handle_msg + executor_address，不写 status——瞬时 handler 的回调可能已提交 status=1，
                    // 整行 updateById 会把 stale status=0 覆盖回去导致误标 status=3
                    jobLogMapper.update(null, new UpdateWrapper<JobLog>()
                            .eq("id", log.getId())
                            .set("handle_msg", "已投递，等待执行器回调")
                            .set("executor_address", log.getExecutorAddress()));
                    job.setTriggerLastTime(System.currentTimeMillis());
                    jobInfoMapper.updateById(job);
                    return;
                }
                lastError = null;  // 明确失败 ack（执行器繁忙等）→ 可重试投递
            } catch (Exception e) {
                lastError = e;
                if (isTimeout(e)) {
                    // ack 读超时：执行器可能已受理但回执丢失，重试=重复执行 → 放弃，等回调/巡检兜底。
                    // 同 ack 成功路径：定点更新，避免 stale status=0 覆盖瞬时回调已提交的 status=1
                    log.setHandleMsg("已投递但未收到受理回执，结果等待执行器回调");
                    jobLogMapper.update(null, new UpdateWrapper<JobLog>()
                            .eq("id", log.getId())
                            .set("handle_msg", log.getHandleMsg())
                            .set("executor_address", log.getExecutorAddress()));
                    return;
                }
                // 连接被拒等 → 可重试投递
            }
        }
        markFailed(log, lastError != null ? lastError.getMessage()
                : (lastResult == null ? "投递失败" : lastResult.getMsg()));
    }

    private void markFailed(JobLog log, String msg) {
        log.setStatus(JobLog.STATUS_FAIL);
        log.setHandleCode(ReturnT.FAIL_CODE);
        log.setHandleMsg(msg);
        log.setHandleTime(LocalDateTime.now());
        jobLogMapper.updateById(log);
    }

    /** RestTemplate 把 SocketTimeoutException 包在 ResourceAccessException 里，沿 cause 链查找。
     *  注意：HttpURLConnection 下连接超时与读超时都是 SocketTimeoutException，仅靠消息区分——
     *  "Connect timed out" = 连接未建立 = 请求未投递 = 重试安全；其余（"Read timed out"）= 已受理应答超时 = 不重试。 */
    private boolean isTimeout(Exception e) {
        if (e == null) return false;
        Throwable c = e;
        while (c != null) {
            if (c instanceof SocketTimeoutException) {
                String msg = c.getMessage();
                // 连接超时（"Connect timed out"）：请求从未投递，重试安全；读超时等其余情况视为结果未知，不重试防重复执行
                if (msg != null && msg.toLowerCase().contains("connect")) return false;
                return true;
            }
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
