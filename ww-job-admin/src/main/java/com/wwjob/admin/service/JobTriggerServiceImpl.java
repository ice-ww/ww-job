package com.wwjob.admin.service;

import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import com.wwjob.core.util.CronUtil;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import com.wwjob.admin.service.JobDecisionService.DecideResult;

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
        trigger(job, triggerType);
    }

    @Override
    public void trigger(JobInfo job, String triggerType) {
        if ("sharding".equalsIgnoreCase(job.getRouteStrategy())) {
            broadcast(job, triggerType);
            return;
        }
        DecideResult result = jobDecisionService.decide(job.getId(), triggerType);
        if (result == null) return;
        dispatch(result, triggerType);
    }

    /** 触发点幂等：行锁内判断本次触发点是否已被别台分配，未分配则先推进 next_time 再放行 */
    @Transactional
    @Override
    public JobInfo claimNextTime(long jobId, String cron) {
        JobInfo job = jobInfoMapper.selectByIdForUpdate(jobId);
        if (job == null || job.getTriggerStatus() == null || job.getTriggerStatus() != 1) {
            return null;   // 任务不存在或已停用
        }
        long now = System.currentTimeMillis();
        if (!claimable(job.getTriggerNextTime(), now)) {
            return null;   // 已被别台推进 / 触发点边界秒尚未整体过去
        }
        long next = CronUtil.nextTime(cron, now);
        jobInfoMapper.advanceNextTime(jobId, next);   // 窄更新推进（A4，已就位）
        job.setTriggerNextTime(next);
        return job;   // 返回锁内新鲜 job，调度器喂给 trigger(job)
    }

    /**
     * 触发点是否已可 claim：next_time 恒为秒边界（CronUtil 秒精度），毫秒级 now 直接比
     * 会在边界秒翻过时误放行相邻点（败者 A 推到 10:47:04.000、败者 B 在 10:47:04.001 读到
     * 10:47:04.000 > 10:47:04.001 为假 → 误 claim → 同秒双触发，F6-2）。
     * 截断到秒：边界秒整体过去（nowSec > lastNext）才放行，杜绝同秒双 claim；正常时间轮
     * 触发恒在 next+1s，nowSec 必大于边界，不受影响。
     */
    static boolean claimable(Long lastNext, long now) {
        if (lastNext == null) return false;
        long nowSec = now - (now % 1000);
        return lastNext < nowSec;
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
            log.setExecutorAddress(addresses.get(i));   // 地址落库进 INSERT（原由 dispatchOne 补写）
            jobLogMapper.insert(log);
            try {
                dispatchOne(log, job, addresses.get(i), total);
            } catch (Exception e) {
                jobLogMapper.endRunning(log.getId(), JobLog.STATUS_FAIL, ReturnT.FAIL_CODE,
                        "投递失败：" + e.getMessage(), LocalDateTime.now());
            }
        }
    }

    private void dispatchOne(JobLog log, JobInfo job, String address, int shardTotal) {
        // 地址在 decide/broadcast 落日志时已写入 DB（P1 去 #10），此处只做 HTTP，不再 updateById 补写
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
            jobInfoMapper.touchLastTime(job.getId(), System.currentTimeMillis());
            return;
        }
        throw new RuntimeException(result != null ? result.getMsg() : "无返回");
    }


    /** 分发：attempt0 直接复用 decide 已路由落库的地址（不再 route、不再补写）；
     *  仅 retry（attempt>0）重新 route，换地址走窄更新。收尾一律 endRunning(status=0 守卫)，
     *  并发回调先落终态时 0 行自然跳过，不覆盖真实结果。 */
    private void dispatch(DecideResult result, String triggerType) {
        JobInfo job = result.getJob();
        JobLog log = result.getLog();
        long jobId = job.getId();
        String firstAddress = log.getExecutorAddress();
        if (firstAddress == null) return;   // decide 已保证无执行器时返回 null 不进来，防御
        int retryCount = job.getRetryCount() == null ? 0 : job.getRetryCount();
        Exception lastError = null;

        for (int attempt = 0; attempt <= retryCount; attempt++) {
            String address;
            if (attempt == 0) {
                address = firstAddress;          // decide 已在 INSERT 前路由并落库
            } else {
                address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), jobId);
                if (address == null) {
                    jobLogMapper.endRunning(log.getId(), JobLog.STATUS_FAIL, ReturnT.FAIL_CODE, "无可用执行器",
                            LocalDateTime.now());
                    return;
                }
                if (!address.equals(log.getExecutorAddress())) {
                    log.setExecutorAddress(address);
                    jobLogMapper.updateExecutorAddress(log.getId(), address);   // 窄更新换地址
                }
            }
            try {
                dispatchOne(log, job, address, 0);
                return;
            } catch (Exception e) {
                lastError = e;
                // 超时 = 结果未知，执行器可能仍在执行：重试必然重复执行，直接放弃
                if (isTimeout(e)) {
                    break;
                }
            }
        }

        if (isTimeout(lastError)) {
            jobLogMapper.endRunning(log.getId(), JobLog.STATUS_UNKNOWN, ReturnT.FAIL_CODE,
                    "执行超时，结果未知：执行器可能仍在执行，请以执行器日志为准，勿重复触发",
                    LocalDateTime.now());
        } else {
            jobLogMapper.endRunning(log.getId(), JobLog.STATUS_FAIL, ReturnT.FAIL_CODE,
                    lastError != null ? lastError.getMessage() : "无返回", LocalDateTime.now());
        }
        jobInfoMapper.touchLastTime(jobId, System.currentTimeMillis());
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
