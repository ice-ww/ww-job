package com.wwjob.admin.dto;

/**
 * @author 王威
 * @version 1.0
 */

import java.util.List;

/** 概览仪表盘聚合统计 */
public class DashboardStats {
    private long jobTotal;
    private long jobEnabled;
    private long jobDisabled;
    private long executorTotal;
    private long executorOnline;
    private long logTotalToday;
    private long logSuccessToday;
    private long logFailToday;
    private long logUnknownToday;
    private List<FailTopItem> failTop;

    public DashboardStats() {
    }

    public DashboardStats(long jobTotal, long jobEnabled, long jobDisabled, long executorTotal, long executorOnline, long logTotalToday, long logSuccessToday, long logFailToday, long logUnknownToday, List<FailTopItem> failTop) {
        this.jobTotal = jobTotal;
        this.jobEnabled = jobEnabled;
        this.jobDisabled = jobDisabled;
        this.executorTotal = executorTotal;
        this.executorOnline = executorOnline;
        this.logTotalToday = logTotalToday;
        this.logSuccessToday = logSuccessToday;
        this.logFailToday = logFailToday;
        this.logUnknownToday = logUnknownToday;
        this.failTop = failTop;
    }

    /**
     * 获取
     * @return jobTotal
     */
    public long getJobTotal() {
        return jobTotal;
    }

    /**
     * 设置
     * @param jobTotal
     */
    public void setJobTotal(long jobTotal) {
        this.jobTotal = jobTotal;
    }

    /**
     * 获取
     * @return jobEnabled
     */
    public long getJobEnabled() {
        return jobEnabled;
    }

    /**
     * 设置
     * @param jobEnabled
     */
    public void setJobEnabled(long jobEnabled) {
        this.jobEnabled = jobEnabled;
    }

    /**
     * 获取
     * @return jobDisabled
     */
    public long getJobDisabled() {
        return jobDisabled;
    }

    /**
     * 设置
     * @param jobDisabled
     */
    public void setJobDisabled(long jobDisabled) {
        this.jobDisabled = jobDisabled;
    }

    /**
     * 获取
     * @return executorTotal
     */
    public long getExecutorTotal() {
        return executorTotal;
    }

    /**
     * 设置
     * @param executorTotal
     */
    public void setExecutorTotal(long executorTotal) {
        this.executorTotal = executorTotal;
    }

    /**
     * 获取
     * @return executorOnline
     */
    public long getExecutorOnline() {
        return executorOnline;
    }

    /**
     * 设置
     * @param executorOnline
     */
    public void setExecutorOnline(long executorOnline) {
        this.executorOnline = executorOnline;
    }

    /**
     * 获取
     * @return logTotalToday
     */
    public long getLogTotalToday() {
        return logTotalToday;
    }

    /**
     * 设置
     * @param logTotalToday
     */
    public void setLogTotalToday(long logTotalToday) {
        this.logTotalToday = logTotalToday;
    }

    /**
     * 获取
     * @return logSuccessToday
     */
    public long getLogSuccessToday() {
        return logSuccessToday;
    }

    /**
     * 设置
     * @param logSuccessToday
     */
    public void setLogSuccessToday(long logSuccessToday) {
        this.logSuccessToday = logSuccessToday;
    }

    /**
     * 获取
     * @return logFailToday
     */
    public long getLogFailToday() {
        return logFailToday;
    }

    /**
     * 设置
     * @param logFailToday
     */
    public void setLogFailToday(long logFailToday) {
        this.logFailToday = logFailToday;
    }

    /**
     * 获取
     * @return logUnknownToday
     */
    public long getLogUnknownToday() {
        return logUnknownToday;
    }

    /**
     * 设置
     * @param logUnknownToday
     */
    public void setLogUnknownToday(long logUnknownToday) {
        this.logUnknownToday = logUnknownToday;
    }

    /**
     * 获取
     * @return failTop
     */
    public List<FailTopItem> getFailTop() {
        return failTop;
    }

    /**
     * 设置
     * @param failTop
     */
    public void setFailTop(List<FailTopItem> failTop) {
        this.failTop = failTop;
    }

    public String toString() {
        return "DashboardStats{jobTotal = " + jobTotal + ", jobEnabled = " + jobEnabled + ", jobDisabled = " + jobDisabled + ", executorTotal = " + executorTotal + ", executorOnline = " + executorOnline + ", logTotalToday = " + logTotalToday + ", logSuccessToday = " + logSuccessToday + ", logFailToday = " + logFailToday + ", logUnknownToday = " + logUnknownToday + ", failTop = " + failTop + "}";
    }
}
