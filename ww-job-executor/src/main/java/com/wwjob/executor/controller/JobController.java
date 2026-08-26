package com.wwjob.executor.controller;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import com.wwjob.executor.handler.JobHandlerRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 王威
 * @version 1.0
 */
@RestController
public class JobController {
    private final JobHandlerRegistry registry;
    public JobController(JobHandlerRegistry registry) { this.registry = registry; }

    @PostMapping("/run")
    public ReturnT<String> run(@RequestBody TriggerParam param) {
        IJobHandler handler = registry.get(param.getHandler());
        if (handler == null) {
            return ReturnT.fail("handler 未注册: " + param.getHandler());
        }
        JobContext ctx = new JobContext();
        ctx.setJobId(param.getJobId());
        ctx.setLogId(param.getLogId());
        ctx.setExecutorParam(param.getExecutorParam());
        ctx.setShardIndex(param.getShardIndex());
        ctx.setShardTotal(param.getShardTotal());
        try {
            return handler.execute(ctx);
        } catch (Exception e) {
            return ReturnT.fail(e.getMessage());
        }
    }
}