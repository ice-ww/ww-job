package com.wwjob.executor.controller;

import com.wwjob.core.context.JobContext;
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
        IJobHandler handler = registry.get(param.getHandler());  //1.找处理器
        if (handler == null) {
            return ReturnT.fail("handler 未注册: " + param.getHandler());
        }
        JobContext ctx = new JobContext();             //2.组装上下文
        ctx.setJobId(param.getJobId());
        ctx.setLogId(param.getLogId());
        ctx.setExecutorParam(param.getExecutorParam());
        ctx.setShardIndex(param.getShardIndex());
        ctx.setShardTotal(param.getShardTotal());
        try {
            jobExecutor.execute(new JobRunner(handler, ctx, callbackReporter));  //3.异步执行
            return ReturnT.success();                  //4.立刻ack
        } catch (RejectedExecutionException e) {
            return ReturnT.fail("执行器繁忙，请稍后");   //5.队列满（线程池采用直接拒绝策略）
        }
    }
}