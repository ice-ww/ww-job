package com.wwjob.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobLogMapper;
import org.springframework.web.bind.annotation.*;

/**
 * @author 王威
 * @version 1.0
 */
@RestController
@RequestMapping("/joblog")
public class JobLogController {
    private final JobLogMapper jobLogMapper;
    public JobLogController(JobLogMapper jobLogMapper) { this.jobLogMapper = jobLogMapper; }

    @GetMapping("/page")
    public Page<JobLog> page(@RequestParam(defaultValue = "1") long page,
                             @RequestParam(defaultValue = "10") long size,
                             @RequestParam(required = false) Long jobId,
                             @RequestParam(required = false) Integer status) {
        QueryWrapper<JobLog> qw = new QueryWrapper<>();
        if (jobId != null) qw.eq("job_id", jobId);
        if (status != null) qw.eq("status", status);
        qw.orderByDesc("id");
        return jobLogMapper.selectPage(new Page<>(page, size), qw);
    }

    @GetMapping("/{id}")
    public JobLog detail(@PathVariable Long id) {
        return jobLogMapper.selectById(id);
    }
}
