package com.wwjob.admin.service;

import com.wwjob.admin.mapper.JobLockMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author 王威
 * @version 1.0
 */

@Service
public class JobLockService {
    private final JobLockMapper jobLockMapper;

    public JobLockService(JobLockMapper jobLockMapper) {
        this.jobLockMapper =jobLockMapper;
    }

    @Transactional
    public void withLock(String lockName, Runnable body) {
        if (jobLockMapper.selectForUpdate(lockName) == null) {
            throw new IllegalStateException("锁不存在: " + lockName + "，检查 schema 初始化");
        }
        body.run();
    }

}
