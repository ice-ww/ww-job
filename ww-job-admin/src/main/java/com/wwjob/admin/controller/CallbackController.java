package com.wwjob.admin.controller;

import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.CallbackParam;
import com.wwjob.core.model.ReturnT;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * @author 王威
 * @version 1.0
 */

/**
 * 执行器回调收账端点：按 logId 把日志更新为真实结果。
 * 最终一致：即使日志已被巡检标记为 status=3，回调到达也覆盖为真实结果；重复回调按 logId 幂等覆盖。
 */
@RestController
public class CallbackController {
    private final JobLogMapper jobLogMapper;

    public CallbackController(JobLogMapper jobLogMapper) {
        this.jobLogMapper = jobLogMapper;
    }

    @PostMapping("/callback")
    public ReturnT<String> callback(@RequestBody CallbackParam param) {
        JobLog log = jobLogMapper.selectById(param.getLogId());
        if (log == null) {
            return ReturnT.fail("logId 不存在: " + param.getLogId());
        }
        log.setStatus(param.getHandleCode() == ReturnT.SUCCESS_CODE
                ? JobLog.STATUS_SUCCESS : JobLog.STATUS_FAIL);
        log.setHandleCode(param.getHandleCode());
        log.setHandleMsg(param.getHandleMsg());
        log.setHandleTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(param.getHandleTime()), ZoneId.systemDefault()));
        jobLogMapper.updateById(log);  // MP 默认忽略 null 字段 → 只更新上述字段
        return ReturnT.success();
    }
}
