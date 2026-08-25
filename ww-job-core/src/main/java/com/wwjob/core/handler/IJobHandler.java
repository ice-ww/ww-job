package com.wwjob.core.handler;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.model.ReturnT;

/**
 * @author 王威
 * @version 1.0
 */
public interface IJobHandler {
    ReturnT<String> execute(JobContext ctx) throws Exception;
}
