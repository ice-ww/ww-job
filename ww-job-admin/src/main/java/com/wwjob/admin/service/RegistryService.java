package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobGroup;
import com.wwjob.admin.entity.JobRegistry;
import com.wwjob.admin.mapper.JobGroupMapper;
import com.wwjob.admin.mapper.JobRegistryMapper;
import com.wwjob.core.model.RegistryParam;
import com.wwjob.core.model.ReturnT;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@Service
public class RegistryService {
    private final JobGroupMapper groupMapper;
    private final JobRegistryMapper registryMapper;
    private final RegistryCacheService registryCacheService;

    public RegistryService(JobGroupMapper groupMapper, JobRegistryMapper registryMapper, RegistryCacheService registryCacheService) {
        this.groupMapper = groupMapper;
        this.registryMapper = registryMapper;
        this.registryCacheService = registryCacheService;
    }


    public ReturnT<String> registry(RegistryParam param) {
        return upsert(param);
    }

    public ReturnT<String> heartbeat(RegistryParam param) {
        return upsert(param);
    }

    private ReturnT<String> upsert(RegistryParam param) {
        JobGroup group = groupMapper.selectOne(
                new QueryWrapper<JobGroup>().eq("app_name", param.getRegistryKey()));
        if (group == null) {
            return ReturnT.fail("执行器分组未注册: " + param.getRegistryKey());
        }
        LocalDateTime now = LocalDateTime.now();
        registryMapper.upsert(group.getId(), param.getRegistryKey(), param.getRegistryValue(), now);
        registryCacheService.touch(group.getId(), param.getRegistryValue(), now);
        return ReturnT.success();
    }

    public ReturnT<String> offline(RegistryParam param) {
        JobGroup group = groupMapper.selectOne(
                new QueryWrapper<JobGroup>().eq("app_name", param.getRegistryKey()));
        if (group == null) {
            return ReturnT.fail("执行器分组未注册: " + param.getRegistryKey());
        }
        registryMapper.delete(new QueryWrapper<JobRegistry>()
                .eq("job_group_id", group.getId())
                .eq("registry_value", param.getRegistryValue()));
        registryCacheService.remove(group.getId(), param.getRegistryValue());
        return ReturnT.success();
    }
}
