package com.wwjob.executor.samples.handler;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.handler.JobHandler;
import com.wwjob.core.model.ReturnT;

/**
 * @author 王威
 * @version 1.0
 */

@JobHandler("demoHandler")
public class DemoHandler implements IJobHandler {
    @Override
    public ReturnT<String> execute(JobContext ctx) {
        String msg = "demo 执行成功, param=" + ctx.getExecutorParam()
                + ", shard=" + ctx.getShardIndex() + "/" + ctx.getShardTotal();
        System.out.println(msg);
        return ReturnT.success(msg);
    }
}
