package com.wwjob.admin.controller;

import com.wwjob.admin.entity.JobGroup;
import com.wwjob.admin.mapper.JobGroupMapper;
import com.wwjob.core.model.ReturnT;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author 王威
 * @version 1.0
 */
@RestController
@RequestMapping("/jobgroup")
public class JobGroupController {
    private final JobGroupMapper groupMapper;
    public JobGroupController(JobGroupMapper groupMapper) { this.groupMapper = groupMapper; }

    @PostMapping
    public ReturnT<Long> create(@RequestBody JobGroup group) {
        groupMapper.insert(group);
        return ReturnT.success(group.getId());
    }

    @GetMapping("/list")
    public List<JobGroup> list() {
        return groupMapper.selectList(null);
    }
}
