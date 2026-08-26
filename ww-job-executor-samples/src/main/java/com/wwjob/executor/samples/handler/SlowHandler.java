package com.wwjob.executor.samples.handler;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.handler.JobHandler;
import com.wwjob.core.model.ReturnT;

/**
 * @author 王威
 * @version 1.0
 */

/**
 * 慢任务：模拟执行超过 admin 读超时（10s）的长任务。
 * 用途：验证"超时不重试 + status=3 未知态 + block_strategy=SINGLE 互斥"的行为。
 * 15s > 10s 读超时 → admin 侧必然超时；
 * 配合 0/5 cron → 每 5s 一次 tick，与 15s 执行期重叠 → 验证 SINGLE 阻塞丢弃。
 */
@JobHandler("slowHandler")
public class SlowHandler implements IJobHandler {
    @Override
    public ReturnT<String> execute(JobContext ctx) {
        long logId = ctx.getLogId();
        try {
            Thread.sleep(15000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReturnT.fail("interrupted");
        }
        String msg = "slow 执行成功, logId=" + logId;
        System.out.println(msg);
        return ReturnT.success(msg);
    }
}
