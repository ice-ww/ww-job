package com.wwjob.executor.controller;

import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import com.wwjob.executor.callback.CallbackReporter;
import com.wwjob.executor.callback.JobRunner;
import com.wwjob.executor.handler.JobHandlerRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * @author 王威
 * @version 1.0
 */
@RestController
public class JobController {
    private final JobHandlerRegistry registry;
    private final ExecutorService jobExecutor;
    private final CallbackReporter callbackReporter;

    public JobController(JobHandlerRegistry registry, ExecutorService jobExecutor,
                         CallbackReporter callbackReporter) {
        this.registry = registry;
        this.jobExecutor = jobExecutor;
        this.callbackReporter = callbackReporter;
    }

    @PostMapping("/run")
    public ReturnT<String> run(@RequestBody TriggerParam param) {
        IJobHandler handler = registry.get(param.getHandler());
        if (handler == null) {
            return ReturnT.fail("handler 未注册: " + param.getHandler());
        }
        try {
            jobExecutor.execute(new JobRunner(handler, param, callbackReporter));
        } catch (RejectedExecutionException e) {
            // 线程池满：拒绝快速失败，admin 视为明确失败可换机重投
            return ReturnT.fail("执行器繁忙，请稍后");
        }
        return ReturnT.success("已受理, logId=" + param.getLogId());
    }
}
