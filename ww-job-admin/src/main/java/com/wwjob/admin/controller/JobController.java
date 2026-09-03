package com.wwjob.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.service.JobTriggerService;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.util.CronUtil;
import org.apache.ibatis.annotations.Delete;
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
                              @RequestParam(defaultValue = "10") long size,
                              @RequestParam(required = false) Long jobGroupId) {
        QueryWrapper<JobInfo> qw = new QueryWrapper<>();
        if (jobGroupId != null) qw.eq("job_group_id", jobGroupId);
        qw.orderByDesc("id");
        return jobInfoMapper.selectPage(new Page<>(page, size), qw);
    }

    @PostMapping("/{id}/trigger")
    public ReturnT<String> trigger(@PathVariable Long id) {
        triggerService.trigger(id, "manual");
        return ReturnT.success();
    }

    @PostMapping("/{id}/start")
    public ReturnT<String> start(@PathVariable Long id) {
        JobInfo job = jobInfoMapper.selectById(id);
        if (job == null) return ReturnT.fail("任务不存在");
        long nextTime = CronUtil.nextTime(job.getCron(), System.currentTimeMillis());
        int rows = jobInfoMapper.startById(id, nextTime);
        return rows > 0
                ? new ReturnT<>(ReturnT.SUCCESS_CODE, "已启动")
                : new ReturnT<>(ReturnT.SUCCESS_CODE, "任务已处于运行状态");
    }

    @PostMapping("/{id}/stop")
    public ReturnT<String> stop(@PathVariable Long id) {
        JobInfo job = jobInfoMapper.selectById(id);
        if (job == null) return ReturnT.fail("任务不存在");
        int rows = jobInfoMapper.stopById(id);
        return rows > 0
                ? new ReturnT<>(ReturnT.SUCCESS_CODE, "已停止")
                : new ReturnT<>(ReturnT.SUCCESS_CODE, "任务已处于停止状态");
    }

    @DeleteMapping("/{id}")
    public ReturnT<String> delete(@PathVariable Long id) {
        jobInfoMapper.deleteById(id);
        return ReturnT.success();
    }
}