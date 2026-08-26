package com.wwjob.admin.service;

/**
 * @author 王威
 * @version 1.0
 */
public interface JobTriggerService {
    void trigger(long jobId, String triggerType);
}
