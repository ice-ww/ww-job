package com.wwjob.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@TableName("job_info")
public class JobInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobGroupId;
    private String jobName;
    private String jobDesc;
    private String handlerName;
    private String executorParam;
    private String cron;
    private String routeStrategy;
    private String blockStrategy;
    private Integer retryCount;
    private Integer timeout;
    private String alarmConfig;
    private Integer triggerStatus;
    private Long triggerNextTime;
    private Long triggerLastTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public JobInfo() {
    }

    public JobInfo(Long id, Long jobGroupId, String jobName, String jobDesc, String handlerName, String executorParam, String cron, String routeStrategy, String blockStrategy, Integer retryCount, Integer timeout, String alarmConfig, Integer triggerStatus, Long triggerNextTime, Long triggerLastTime, LocalDateTime createTime, LocalDateTime updateTime) {
        this.id = id;
        this.jobGroupId = jobGroupId;
        this.jobName = jobName;
        this.jobDesc = jobDesc;
        this.handlerName = handlerName;
        this.executorParam = executorParam;
        this.cron = cron;
        this.routeStrategy = routeStrategy;
        this.blockStrategy = blockStrategy;
        this.retryCount = retryCount;
        this.timeout = timeout;
        this.alarmConfig = alarmConfig;
        this.triggerStatus = triggerStatus;
        this.triggerNextTime = triggerNextTime;
        this.triggerLastTime = triggerLastTime;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getJobGroupId() {
        return jobGroupId;
    }

    public void setJobGroupId(Long jobGroupId) {
        this.jobGroupId = jobGroupId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getHandlerName() {
        return handlerName;
    }

    public void setHandlerName(String handlerName) {
        this.handlerName = handlerName;
    }

    public String getJobDesc() {
        return jobDesc;
    }

    public void setJobDesc(String jobDesc) {
        this.jobDesc = jobDesc;
    }

    public String getExecutorParam() {
        return executorParam;
    }

    public void setExecutorParam(String executorParam) {
        this.executorParam = executorParam;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getRouteStrategy() {
        return routeStrategy;
    }

    public void setRouteStrategy(String routeStrategy) {
        this.routeStrategy = routeStrategy;
    }

    public String getBlockStrategy() {
        return blockStrategy;
    }

    public void setBlockStrategy(String blockStrategy) {
        this.blockStrategy = blockStrategy;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public String getAlarmConfig() {
        return alarmConfig;
    }

    public void setAlarmConfig(String alarmConfig) {
        this.alarmConfig = alarmConfig;
    }

    public Long getTriggerNextTime() {
        return triggerNextTime;
    }

    public void setTriggerNextTime(Long triggerNextTime) {
        this.triggerNextTime = triggerNextTime;
    }

    public Integer getTriggerStatus() {
        return triggerStatus;
    }

    public void setTriggerStatus(Integer triggerStatus) {
        this.triggerStatus = triggerStatus;
    }

    public Long getTriggerLastTime() {
        return triggerLastTime;
    }

    public void setTriggerLastTime(Long triggerLastTime) {
        this.triggerLastTime = triggerLastTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public String toString() {
        return "JobInfo{id = " + id + ", jobGroupId = " + jobGroupId + ", jobName = " + jobName + ", jobDesc = " + jobDesc + ", handlerName = " + handlerName + ", executorParam = " + executorParam + ", cron = " + cron + ", routeStrategy = " + routeStrategy + ", blockStrategy = " + blockStrategy + ", retryCount = " + retryCount + ", timeout = " + timeout + ", alarmConfig = " + alarmConfig + ", triggerStatus = " + triggerStatus + ", triggerNextTime = " + triggerNextTime + ", triggerLastTime = " + triggerLastTime + ", createTime = " + createTime + ", updateTime = " + updateTime + "}";
    }
}
