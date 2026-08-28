package com.wwjob.executor.samples.handler;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.handler.JobHandler;
import com.wwjob.core.model.ReturnT;

/**
 * @author 王威
 * @version 1.0
 */

@JobHandler("failDemoHandler")
public class FailDemoHandler implements IJobHandler {
    @Override
    public ReturnT<String> execute(JobContext ctx) throws Exception {
        return ReturnT.fail("模拟业务失败");
    }
}
