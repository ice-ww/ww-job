package com.wwjob.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobRegistry;
import com.wwjob.admin.mapper.JobRegistryMapper;
import com.wwjob.admin.service.RegistryService;
import com.wwjob.core.model.RegistryParam;
import com.wwjob.core.model.ReturnT;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author 王威
 * @version 1.0
 */
@RestController
public class RegistryController {
    private final RegistryService registryService;
    private final JobRegistryMapper registryMapper;
    public RegistryController(RegistryService registryService, JobRegistryMapper registryMapper) {
        this.registryService = registryService;
        this.registryMapper = registryMapper;
    }

    @PostMapping("/registry")
    public ReturnT<String> registry(@RequestBody RegistryParam param) {
        return registryService.registry(param);
    }

    @GetMapping("/registry/list")
    public List<JobRegistry> list() {
        return registryMapper.selectList(new QueryWrapper<JobRegistry>()
                .orderByAsc("job_group_id"));
    }

    @PostMapping("/registry/offline")
    public ReturnT<String> offline(@RequestBody RegistryParam param) {
        return registryService.offline(param);
    }

    @PostMapping("/heartbeat")
    public ReturnT<String> heartbeat(@RequestBody RegistryParam param) {
        return registryService.heartbeat(param);
    }
}
