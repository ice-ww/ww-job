package com.wwjob.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */

@TableName("job_lock")
public class JobLock {
    /** 锁名，String 主键（非自增） */
    @TableId(value = "lock_name", type = IdType.INPUT)
    private String lockName;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public JobLock() {
    }

    public String getLockName() { return lockName; }
    public void setLockName(String lockName) { this.lockName = lockName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
