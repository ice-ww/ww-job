package com.wwjob.executor.callback;

/**
 * @author 王威
 * @version 1.0
 */

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.model.CallbackParam;
import com.wwjob.core.model.ReturnT;


/**
 * 异步执行单元：跑 handler，无论成败都构造 CallbackParam 回调给 admin。
 */
public class JobRunner implements Runnable {
    private final IJobHandler handler;
    private final JobContext ctx;
    private final CallbackReporter reporter;


    public JobRunner(IJobHandler handler, JobContext ctx, CallbackReporter reporter) {
        this.handler = handler;
        this.ctx = ctx;
        this.reporter = reporter;
    }

    @Override
    public void run() {
        ReturnT<String> result;
        try {
            result = handler.execute(ctx);
        } catch (Exception e) {
            result = ReturnT.fail(e.getMessage());
        }
        if (result == null) {
            result = ReturnT.fail("handler 返回 null, 疑似实现缺陷");
        }
        String handleMsg = result.getMsg();
        if (handleMsg == null && result.getData() != null) {
            handleMsg = result.getData();
        }
        reporter.report(new CallbackParam(ctx.getLogId(),
                result.getCode(), handleMsg, System.currentTimeMillis()));

    }
}
