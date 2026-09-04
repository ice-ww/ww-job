package com.wwjob.admin.service;

import com.wwjob.admin.entity.JobInfo;

/**
 * @author 王威
 * @version 1.0
 */
public interface JobTriggerService {
    /** 手动/API 触发：selectById 一次后委托 trigger(job,...) */
    void trigger(long jobId, String triggerType);

    /** 实体触发入口：cron 路径喂入 claim 锁内新鲜 job（不再二次查询）；manual 内部复用 */
    void trigger(JobInfo job, String triggerType);

    /** 抢行锁 + 窄更新推进 next_time，返回锁内新鲜 job（非 null 才允许触发）。
     *  多 admin 下同一触发点只有一台返回非 null（先推进者），其余返回 null。 */
    JobInfo claimNextTime(long jobId, String cron);

    /** cron 到期（非 sharding 快路径）：claim+decide 合并单事务决策，事务提交后内部 dispatch（HTTP 在事务外）。 */
    void triggerCronFast(long jobId, String cron);
}
