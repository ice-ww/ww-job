package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobRegistry;
import com.wwjob.admin.mapper.JobRegistryMapper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@Component
@EnableScheduling
public class RegistryCleaner {
    private static final int EXPIRE_SECONDS = 90;
    private final JobRegistryMapper registryMapper;
    public RegistryCleaner(JobRegistryMapper registryMapper) { this.registryMapper = registryMapper; }

    @Scheduled(fixedRate = 10000)
    public void clean() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(EXPIRE_SECONDS);
        registryMapper.delete(new QueryWrapper<JobRegistry>().lt("heartbeat_time", threshold));
    }
}
