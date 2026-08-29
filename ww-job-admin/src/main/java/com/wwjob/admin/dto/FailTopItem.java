package com.wwjob.admin.dto;

/**
 * @author 王威
 * @version 1.0
 */

/** 今日失败 TOP 任务 */
public class FailTopItem {
    private long jobId;
    private String jobName;
    private String handlerName;
    private long failCount;

    public FailTopItem() {}


    public FailTopItem(long jobId, String jobName, String handlerName, long failCount) {
        this.jobId = jobId;
        this.jobName = jobName;
        this.handlerName = handlerName;
        this.failCount = failCount;
    }

    /**
     * 获取
     * @return jobId
     */
    public long getJobId() {
        return jobId;
    }

    /**
     * 设置
     * @param jobId
     */
    public void setJobId(long jobId) {
        this.jobId = jobId;
    }

    /**
     * 获取
     * @return jobName
     */
    public String getJobName() {
        return jobName;
    }

    /**
     * 设置
     * @param jobName
     */
    public void setJobName(String jobName) {
        this.jobName = jobName;
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
     * @return failCount
     */
    public long getFailCount() {
        return failCount;
    }

    /**
     * 设置
     * @param failCount
     */
    public void setFailCount(long failCount) {
        this.failCount = failCount;
    }

    public String toString() {
        return "FailTopItem{jobId = " + jobId + ", jobName = " + jobName + ", handlerName = " + handlerName + ", failCount = " + failCount + "}";
    }
}
