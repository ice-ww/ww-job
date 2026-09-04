package com.wwjob.admin.service;

import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.util.CronUtil;
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

    /** 手动/API 决策：行锁读 → 锁下决策核心。事务在方法级开启，gate/route/insert 同锁原子。
     *  返回已落库的 log + 行锁内最新 job 供 dispatch 直接用；无执行器/被阻塞/无任务 → 返回 null。 */
    @Transactional
    public DecideResult decide(long jobId, String triggerType) {
        JobInfo job = jobInfoMapper.selectByIdForUpdate(jobId);
        if (job == null) {
            return null;
        }
        return decideUnderLock(job, triggerType);
    }

    /** cron 到期触发决策（非 sharding）：单事务内 锁行 → status/claimable 门 → advance → SINGLE gate → route → INSERT。
     *  返回 DecideResult 供事务外 dispatch；停用/边界未到/被阻塞/无执行器 → null（已落账或无需落账）。
     *  事务次序钉死：advance 先于 gate（B-1，blocked 也消费本边界）；HTTP 由调用方在事务外分发。 */
    @Transactional
    public DecideResult decideCron(long jobId, String cron) {
        JobInfo job = claimLocked(jobId, cron);
        if (job == null) {
            return null;
        }
        return decideUnderLock(job, "cron");
    }

    /** 锁下 claim 核心（非事务，须在调用方持锁事务内）：status 门 → claimable 门 → advanceNextTime。
     *  停用 / 边界未到 / 已被别台推进 → null。advance 先于一切 gate（B-1：blocked 也消费本边界）。 */
    public JobInfo claimLocked(long jobId, String cron) {
        JobInfo job = jobInfoMapper.selectByIdForUpdate(jobId);
        if (job == null || job.getTriggerStatus() == null || job.getTriggerStatus() != 1) {
            return null;   // 任务不存在或已停用
        }
        long now = System.currentTimeMillis();
        if (!claimable(job.getTriggerNextTime(), now)) {
            return null;   // 已被别台推进 / 触发点边界秒尚未整体过去
        }
        long next = CronUtil.nextTime(cron, now);
        jobInfoMapper.advanceNextTime(jobId, next);   // A4 窄更新
        job.setTriggerNextTime(next);
        return job;   // 推进后的锁内新鲜 job
    }

    /** 锁下决策核心（非事务，须在调用方持锁事务内）：SINGLE gate → route → INSERT。
     *  入参 job 必须是行锁内读到的最新行（claimLocked/decide 已保证）。 */
    public DecideResult decideUnderLock(JobInfo job, String triggerType) {
        boolean single = "SINGLE".equalsIgnoreCase(job.getBlockStrategy());
        if (single) {
            long running = jobLogMapper.countRunning(job.getId());
            if (running > 0) {
                // 上一次执行尚未结束：丢弃本次触发。STATUS_BLOCKED=4（item5 拆分后口径）：被丢弃≠超时未知
                // （handle_time=null ⇒ 不入告警/巡检，Dashboard 单独可见）；blocked 永不收回调，收账守卫 IN(0,3) 天然不含 4
                insertLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃",
                        JobLog.STATUS_BLOCKED, null, null, null);
                return null;
            }
        }
        // SINGLE gate 之后才 route：被阻塞的触发不消耗 registry 读
        String address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), job.getId());
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
