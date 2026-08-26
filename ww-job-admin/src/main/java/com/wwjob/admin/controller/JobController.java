package com.wwjob.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.service.JobTriggerService;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.util.CronUtil;
import org.springframework.web.bind.annotation.*;

/**
 * @author 王威
 * @version 1.0
 */
@RestController
@RequestMapping("/job")
public class JobController {
    private final JobInfoMapper jobInfoMapper;
    private final JobTriggerService triggerService;

    public JobController(JobInfoMapper jobInfoMapper, JobTriggerService triggerService) {
        this.jobInfoMapper = jobInfoMapper;
        this.triggerService = triggerService;
    }

    @PostMapping
    public ReturnT<Long> create(@RequestBody JobInfo job) {
        job.setTriggerNextTime(CronUtil.nextTime(job.getCron(), System.currentTimeMillis()));
        jobInfoMapper.insert(job);
        return ReturnT.success(job.getId());
    }

    @PutMapping
    public ReturnT<String> update(@RequestBody JobInfo job) {
        job.setTriggerNextTime(CronUtil.nextTime(job.getCron(), System.currentTimeMillis()));
        jobInfoMapper.updateById(job);
        return ReturnT.success();
    }

    @GetMapping("/page")
    public Page<JobInfo> page(@RequestParam(defaultValue = "1") long page,
                              @RequestParam(defaultValue = "10") long size) {
        return jobInfoMapper.selectPage(new Page<>(page, size),
                new QueryWrapper<JobInfo>().orderByDesc("id"));
    }

    @PostMapping("/{id}/trigger")
    public ReturnT<String> trigger(@PathVariable Long id) {
        triggerService.trigger(id, "manual");
        return ReturnT.success();
    }

    @PostMapping("/{id}/start")
    public ReturnT<String> start(@PathVariable Long id) {
        JobInfo job = jobInfoMapper.selectById(id);
        job.setTriggerStatus(1);
        job.setTriggerNextTime(CronUtil.nextTime(job.getCron(), System.currentTimeMillis()));
        jobInfoMapper.updateById(job);
        return ReturnT.success();
    }

    @PostMapping("/{id}/stop")
    public ReturnT<String> stop(@PathVariable Long id) {
        JobInfo job = jobInfoMapper.selectById(id);
        job.setTriggerStatus(0);
        jobInfoMapper.updateById(job);
        return ReturnT.success();
    }
}