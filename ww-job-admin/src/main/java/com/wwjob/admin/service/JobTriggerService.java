package com.wwjob.admin.service;

/**
 * @author 王威
 * @version 1.0
 */
public interface JobTriggerService {
    void trigger(long jobId, String triggerType);

    /**
     * 抢行锁 + 前置推进 next_time：返回 true 才允许触发。
     * 多 admin 下同一触发点只有一台会返回 true（先推进者），其余跳过。
     */
    boolean claimNextTime(long jobId, String cron);
}
