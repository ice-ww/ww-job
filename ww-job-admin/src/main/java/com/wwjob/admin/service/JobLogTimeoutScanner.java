package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobLogMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author 王威
 * @version 1.0
 */

@Component
public class JobLogTimeoutScanner {
    private final JobLogMapper jobLogMapper;

    public JobLogTimeoutScanner(JobLogMapper jobLogMapper) {
        this.jobLogMapper = jobLogMapper;
    }

    @Scheduled(fixedRate = 30000)
    public void scan() {
        // 「现在」与写 trigger_time 同用 JVM 时钟：now 作参数传 SQL，避免 NOW() 随 DB 会话时区错位
        LocalDateTime now = LocalDateTime.now();
        List<Long> timeoutLogIds = jobLogMapper.selectTimeoutLogIds(now);
        for (Long logId : timeoutLogIds) {
            jobLogMapper.update(null, new UpdateWrapper<JobLog>()
                    .eq("id", logId)
                    .set("status", JobLog.STATUS_UNKNOWN)
                    .set("handle_msg", "执行超时未收到回调，结果未知")
                    .set("handle_time", now));   // ← 每行都用同一个,保证时间一致
        }
    }

}
