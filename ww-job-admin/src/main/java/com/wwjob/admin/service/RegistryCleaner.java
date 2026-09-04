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
    private final JobRegistryMapper registryMapper;
    private final RegistryCacheService registryCacheService;

    public RegistryCleaner(JobRegistryMapper registryMapper, RegistryCacheService registryCacheService) { this.registryMapper = registryMapper;
        this.registryCacheService = registryCacheService;
    }

    @Scheduled(fixedRate = 10000)
    public void clean() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(JobRegistry.ONLINE_SECONDS);
        registryMapper.delete(new QueryWrapper<JobRegistry>().lt("heartbeat_time", threshold));
        registryCacheService.prune(threshold);   // 同 90s 阈值：缓存与 DB cleaner 同语义
    }
}
