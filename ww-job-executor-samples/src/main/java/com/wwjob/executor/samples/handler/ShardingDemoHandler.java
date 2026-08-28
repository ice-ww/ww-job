package com.wwjob.executor.samples.handler;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.handler.JobHandler;
import com.wwjob.core.model.ReturnT;


/**
 * @author 王威
 * @version 1.0
 */

@JobHandler("shardingDemoHandler")
public class ShardingDemoHandler implements IJobHandler {
    @Override
    public ReturnT<String> execute(JobContext ctx) {
        int shardIndex = ctx.getShardIndex();
        int shardTotal = ctx.getShardTotal();
        String msg = "shardingDemo 执行成功, shardIndex =" + shardIndex + ", shardTotal =" + shardTotal;
        System.out.println(msg);
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标志，别吞掉中断
        }
        return ReturnT.success(msg);
    }
}
