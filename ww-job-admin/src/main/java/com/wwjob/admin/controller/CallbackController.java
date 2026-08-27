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
        JobLog log = jobLogMapper.selectById(param.getLogId());
        if (log == null) {
            return ReturnT.fail("logId 不存在:" + param.getLogId());
        }
        log.setStatus(param.getHandleCode() == ReturnT.SUCCESS_CODE ? JobLog.STATUS_SUCCESS : JobLog.STATUS_FAIL);
        log.setHandleCode(param.getHandleCode());
        log.setHandleMsg(param.getHandleMsg());
        log.setHandleTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(param.getHandleTime()), ZoneId.systemDefault()));
        jobLogMapper.updateById(log);
        return ReturnT.success();
    }

}
