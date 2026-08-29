package com.wwjob.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */

@TableName("job_alert_state")
public class JobAlertState {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    /** 上次告警毫秒时间戳（10min 去重窗口） */
    private Long lastAlertAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public JobAlertState() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public Long getLastAlertAt() { return lastAlertAt; }
    public void setLastAlertAt(Long lastAlertAt) { this.lastAlertAt = lastAlertAt; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
