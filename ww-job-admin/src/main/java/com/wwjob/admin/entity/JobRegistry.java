package com.wwjob.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@TableName("job_registry")
public class JobRegistry {
    /** 在线判定口径：心跳超过该秒数视为离线（cleaner / dashboard / 路由共用，避免口径漂移） */
    public static final int ONLINE_SECONDS = 90;
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobGroupId;
    private String registryKey;
    private String registryValue;
    private LocalDateTime heartbeatTime;
    private LocalDateTime updateTime;

    public JobRegistry() {
    }

    public JobRegistry(Long id, Long jobGroupId, String registryKey, String registryValue, LocalDateTime heartbeatTime, LocalDateTime updateTime) {
        this.id = id;
        this.jobGroupId = jobGroupId;
        this.registryKey = registryKey;
        this.registryValue = registryValue;
        this.heartbeatTime = heartbeatTime;
        this.updateTime = updateTime;
    }

    /**
     * 获取
     * @return id
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置
     * @param id
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取
     * @return jobGroupId
     */
    public Long getJobGroupId() {
        return jobGroupId;
    }

    /**
     * 设置
     * @param jobGroupId
     */
    public void setJobGroupId(Long jobGroupId) {
        this.jobGroupId = jobGroupId;
    }

    /**
     * 获取
     * @return registryKey
     */
    public String getRegistryKey() {
        return registryKey;
    }

    /**
     * 设置
     * @param registryKey
     */
    public void setRegistryKey(String registryKey) {
        this.registryKey = registryKey;
    }

    /**
     * 获取
     * @return registryValue
     */
    public String getRegistryValue() {
        return registryValue;
    }

    /**
     * 设置
     * @param registryValue
     */
    public void setRegistryValue(String registryValue) {
        this.registryValue = registryValue;
    }

    /**
     * 获取
     * @return heartbeatTime
     */
    public LocalDateTime getHeartbeatTime() {
        return heartbeatTime;
    }

    /**
     * 设置
     * @param heartbeatTime
     */
    public void setHeartbeatTime(LocalDateTime heartbeatTime) {
        this.heartbeatTime = heartbeatTime;
    }

    /**
     * 获取
     * @return updateTime
     */
    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    /**
     * 设置
     * @param updateTime
     */
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public String toString() {
        return "JobRegistry{id = " + id + ", jobGroupId = " + jobGroupId + ", registryKey = " + registryKey + ", registryValue = " + registryValue + ", heartbeatTime = " + heartbeatTime + ", updateTime = " + updateTime + "}";
    }
}

