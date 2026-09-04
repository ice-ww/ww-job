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
    private final ExecutorRouterService routerService;

    public JobDecisionService(JobInfoMapper jobInfoMapper, JobLogMapper jobLogMapper,
                              ExecutorRouterService routerService) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobLogMapper = jobLogMapper;
        this.routerService = routerService;
    }

    /** 决策：行锁内判定 SINGLE 互斥（门）→ 门后 route → 地址直接写进 running log 的 INSERT。
     *  返回已落库的 log + 行锁内最新 job 供 dispatch 直接用；无执行器/被阻塞/无任务 → 返回 null。 */
    @Transactional
    public DecideResult decide(long jobId, String triggerType) {
        JobInfo job = jobInfoMapper.selectByIdForUpdate(jobId);
        if (job == null) {
            return null;
        }
        boolean single = "SINGLE".equalsIgnoreCase(job.getBlockStrategy());
        if (single) {
            long running = jobLogMapper.countRunning(jobId);
            if (running > 0) {
                // 上一次执行尚未结束：丢弃本次触发。STATUS_BLOCKED=4（item5 拆分后口径）：被丢弃≠超时未知
                // （handle_time=null ⇒ 不入告警/巡检，Dashboard 单独可见）；blocked 永不收回调，收账守卫 IN(0,3) 天然不含 4
                insertLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃",
                        JobLog.STATUS_BLOCKED, null, null, null);
                return null;
            }
        }
        // SINGLE gate 之后才 route：被阻塞的触发不消耗 registry 读
        String address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), jobId);
        if (address == null) {
            // 无可用执行器：直接落失败日志（原 running→fail 两次写收敛为一次写），不分发。
            // handleCode/handleTime 保持空 —— 与旧 route==null 立即返回分支一致（旧代码此处不置位，
            // 使该失败不进入「最近失败」告警扫描；改置位会静默改变告警可见性，故不收口）
            insertLog(job, triggerType, "无可用执行器",
                    JobLog.STATUS_FAIL, null, null, null);
            return null;
        }
        JobLog log = insertLog(job, triggerType, "已受理，等待执行结果",
                JobLog.STATUS_RUNNING, null, null, address);
        return new DecideResult(log, job);
    }

    /** 通用落库：插入一条 job_log（id 由 DB 回填）。handleCode/handleTime/executorAddress 可空。 */
    private JobLog insertLog(JobInfo job, String triggerType, String handleMsg, int status,
                             Integer handleCode, LocalDateTime handleTime, String executorAddress) {
        JobLog log = new JobLog();
        log.setJobId(job.getId());
        log.setJobGroupId(job.getJobGroupId());
        log.setHandlerName(job.getHandlerName());
        log.setTriggerType(triggerType);
        log.setTriggerTime(LocalDateTime.now());
        log.setStatus(status);
        log.setHandleMsg(handleMsg);
        if (handleCode != null) log.setHandleCode(handleCode);
        if (handleTime != null) log.setHandleTime(handleTime);
        if (executorAddress != null) log.setExecutorAddress(executorAddress);
        log.setShardIndex(0);   // 非分片路径恒为 0；DB DEFAULT 0 不会回填内存实体，dispatchOne 拆箱会 NPE
        jobLogMapper.insert(log);
        return log;
    }

    /** 调度决策结果载具：携带已落库 log 与行锁内最新 job，dispatch 直接用，省去二次查询 */
    public static class DecideResult {
        private final JobLog log;
        private final JobInfo job;
        public DecideResult(JobLog log, JobInfo job) { this.log = log; this.job = job; }
        public JobLog getLog() { return log; }
        public JobInfo getJob() { return job; }
    }
}
