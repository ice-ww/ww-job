package com.wwjob.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@TableName("job_log")
public class JobLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private Long jobGroupId;
    private String executorAddress;
    private String handlerName;
    private String triggerType;
    private LocalDateTime triggerTime;
    private LocalDateTime handleTime;
    private Integer handleCode;
    private String handleMsg;
    private Integer status;
    private LocalDateTime createTime;

    public JobLog() {
    }

    public JobLog(Long id, Long jobId, Long jobGroupId, String executorAddress, String handlerName, String triggerType, LocalDateTime triggerTime, LocalDateTime handleTime, Integer handleCode, String handleMsg, Integer status, LocalDateTime createTime) {
        this.id = id;
        this.jobId = jobId;
        this.jobGroupId = jobGroupId;
        this.executorAddress = executorAddress;
        this.handlerName = handlerName;
        this.triggerType = triggerType;
        this.triggerTime = triggerTime;
        this.handleTime = handleTime;
        this.handleCode = handleCode;
        this.handleMsg = handleMsg;
        this.status = status;
        this.createTime = createTime;
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
     * @return jobId
     */
    public Long getJobId() {
        return jobId;
    }

    /**
     * 设置
     * @param jobId
     */
    public void setJobId(Long jobId) {
        this.jobId = jobId;
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
     * @return executorAddress
     */
    public String getExecutorAddress() {
        return executorAddress;
    }

    /**
     * 设置
     * @param executorAddress
     */
    public void setExecutorAddress(String executorAddress) {
        this.executorAddress = executorAddress;
    }

    /**
     * 获取
     * @return handlerName
     */
    public String getHandlerName() {
        return handlerName;
    }

    /**
     * 设置
     * @param handlerName
     */
    public void setHandlerName(String handlerName) {
        this.handlerName = handlerName;
    }

    /**
     * 获取
     * @return triggerType
     */
    public String getTriggerType() {
        return triggerType;
    }

    /**
     * 设置
     * @param triggerType
     */
    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    /**
     * 获取
     * @return triggerTime
     */
    public LocalDateTime getTriggerTime() {
        return triggerTime;
    }

    /**
     * 设置
     * @param triggerTime
     */
    public void setTriggerTime(LocalDateTime triggerTime) {
        this.triggerTime = triggerTime;
    }

    /**
     * 获取
     * @return handleTime
     */
    public LocalDateTime getHandleTime() {
        return handleTime;
    }

    /**
     * 设置
     * @param handleTime
     */
    public void setHandleTime(LocalDateTime handleTime) {
        this.handleTime = handleTime;
    }

    /**
     * 获取
     * @return handleCode
     */
    public Integer getHandleCode() {
        return handleCode;
    }

    /**
     * 设置
     * @param handleCode
     */
    public void setHandleCode(Integer handleCode) {
        this.handleCode = handleCode;
    }

    /**
     * 获取
     * @return handleMsg
     */
    public String getHandleMsg() {
        return handleMsg;
    }

    /**
     * 设置
     * @param handleMsg
     */
    public void setHandleMsg(String handleMsg) {
        this.handleMsg = handleMsg;
    }

    /**
     * 获取
     * @return status
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 设置
     * @param status
     */
    public void setStatus(Integer status) {
        this.status = status;
    }

    /**
     * 获取
     * @return createTime
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置
     * @param createTime
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public String toString() {
        return "JobLog{id = " + id + ", jobId = " + jobId + ", jobGroupId = " + jobGroupId + ", executorAddress = " + executorAddress + ", handlerName = " + handlerName + ", triggerType = " + triggerType + ", triggerTime = " + triggerTime + ", handleTime = " + handleTime + ", handleCode = " + handleCode + ", handleMsg = " + handleMsg + ", status = " + status + ", createTime = " + createTime + "}";
    }
}

