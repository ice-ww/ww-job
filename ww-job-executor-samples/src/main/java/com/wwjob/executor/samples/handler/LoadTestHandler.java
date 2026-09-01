package com.wwjob.executor.samples.handler;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.handler.JobHandler;
import com.wwjob.core.model.ReturnT;

/**
 * @author 王威
 * @version 1.0
 */

@JobHandler("loadTestHandler")
public class LoadTestHandler implements IJobHandler {
    @Override
    public ReturnT<String> execute(JobContext ctx) {
        String param = ctx.getExecutorParam();          // param 从 JobContext 取
        if ("fail".equals(param)) {
            throw new RuntimeException("load test fail");
        }
        int ms = 0;
        try { ms = Integer.parseInt(param); } catch (Exception ignored) {}
        if (ms > 0) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        long sum = 0;
        for (int i = 0; i < 1000; i++) sum += i; // 防 JIT 优化掉
        return ReturnT.success("load test ok");
    }
}
