package com.wwjob.core.model;

/**
 * @author 王威
 * @version 1.0
 */
public class TriggerParam {
    private long jobId;
    private String handler;
    private String executorParam;
    private int shardIndex;
    private int shardTotal;
    private long logId;

    public TriggerParam() {
    }

    public TriggerParam(long jobId, String handler, String executorParam, int shardIndex, int shardTotal, long logId) {
        this.jobId = jobId;
        this.handler = handler;
        this.executorParam = executorParam;
        this.shardIndex = shardIndex;
        this.shardTotal = shardTotal;
        this.logId = logId;
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
     * @return handler
     */
    public String getHandler() {
        return handler;
    }

    /**
     * 设置
     * @param handler
     */
    public void setHandler(String handler) {
        this.handler = handler;
    }

    /**
     * 获取
     * @return executorParam
     */
    public String getExecutorParam() {
        return executorParam;
    }

    /**
     * 设置
     * @param executorParam
     */
    public void setExecutorParam(String executorParam) {
        this.executorParam = executorParam;
    }

    /**
     * 获取
     * @return shardIndex
     */
    public int getShardIndex() {
        return shardIndex;
    }

    /**
     * 设置
     * @param shardIndex
     */
    public void setShardIndex(int shardIndex) {
        this.shardIndex = shardIndex;
    }

    /**
     * 获取
     * @return shardTotal
     */
    public int getShardTotal() {
        return shardTotal;
    }

    /**
     * 设置
     * @param shardTotal
     */
    public void setShardTotal(int shardTotal) {
        this.shardTotal = shardTotal;
    }

    /**
     * 获取
     * @return logId
     */
    public long getLogId() {
        return logId;
    }

    /**
     * 设置
     * @param logId
     */
    public void setLogId(long logId) {
        this.logId = logId;
    }

    public String toString() {
        return "TriggerParam{jobId = " + jobId + ", handler = " + handler + ", executorParam = " + executorParam + ", shardIndex = " + shardIndex + ", shardTotal = " + shardTotal + ", logId = " + logId + "}";
    }
}
