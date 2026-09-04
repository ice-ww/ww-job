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
@RestController
public class CallbackController {
    private final JobLogMapper jobLogMapper;

    public CallbackController(JobLogMapper jobLogMapper) {
        this.jobLogMapper = jobLogMapper;
    }

    @PostMapping("/callback")
    public ReturnT<String> callback(@RequestBody CallbackParam param){
        int status = param.getHandleCode() == ReturnT.SUCCESS_CODE ? JobLog.STATUS_SUCCESS : JobLog.STATUS_FAIL;
        LocalDateTime handleTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(param.getHandleTime()), ZoneId.systemDefault());
        int rows = jobLogMapper.completeById(param.getLogId(), status,
                param.getHandleCode(), param.getHandleMsg(), handleTime);
        if (rows > 0) {
            return ReturnT.success();
        }
        // 0 行：要么 logId 不存在，要么已终态（幂等）。只在罕见分支查一次库
        return jobLogMapper.selectById(param.getLogId()) == null
                ? ReturnT.fail("logId 不存在:" + param.getLogId())
                : ReturnT.success("已是最新状态，忽略重复回调");
    }

}
