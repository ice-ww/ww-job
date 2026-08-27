package com.wwjob.executor.callback;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.model.CallbackParam;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;

/**
 * @author 王威
 * @version 1.0
 */

/**
 * 异步执行单元：跑 handler，无论成败都构造 CallbackParam 回调给 admin。
 */
public class JobRunner implements Runnable {
    private final IJobHandler handler;
    private final TriggerParam param;
    private final CallbackReporter reporter;

    public JobRunner(IJobHandler handler, TriggerParam param, CallbackReporter reporter) {
        this.handler = handler;
        this.param = param;
        this.reporter = reporter;
    }

    @Override
    public void run() {
        JobContext ctx = new JobContext();
        ctx.setJobId(param.getJobId());
        ctx.setLogId(param.getLogId());
        ctx.setExecutorParam(param.getExecutorParam());
        ctx.setShardIndex(param.getShardIndex());
        ctx.setShardTotal(param.getShardTotal());
        ReturnT<String> result;
        try {
            result = handler.execute(ctx);
        } catch (Exception e) {
            result = ReturnT.fail(e.getMessage());
        }
        String handleMsg = result.getMsg();
        if (handleMsg == null) {
            // ReturnT.success(data) 把结果消息放进 data；data 也 null（裸 success()）则保持 null，避免落库 "null" 字符串
            Object data = result.getData();
            handleMsg = data != null ? String.valueOf(data) : null;
        }
        reporter.report(new CallbackParam(param.getLogId(),
                result.getCode(), handleMsg, System.currentTimeMillis()));
    }
}
