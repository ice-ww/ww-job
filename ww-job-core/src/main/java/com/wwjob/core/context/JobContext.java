package com.wwjob.core.context;

/**
 * @author 王威
 * @version 1.0
 */
public class JobContext {
    private long jobId;
    private long logId;
    private String executorParam;
    private int shardIndex;
    private int shardTotal;


    public JobContext() {
    }

    public JobContext(long jobId, long logId, String executorParam, int shardIndex, int shardTotal) {
        this.jobId = jobId;
        this.logId = logId;
        this.executorParam = executorParam;
        this.shardIndex = shardIndex;
        this.shardTotal = shardTotal;
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

    public String toString() {
        return "JobContext{jobId = " + jobId + ", logId = " + logId + ", executorParam = " + executorParam + ", shardIndex = " + shardIndex + ", shardTotal = " + shardTotal + "}";
    }
}
