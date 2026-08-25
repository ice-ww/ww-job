package com.wwjob.core.model;

/**
 * @author 王威
 * @version 1.0
 */
public class LogParam {
    private long logId;
    private String content;

    public LogParam() {
    }

    public LogParam(long logId, String content) {
        this.logId = logId;
        this.content = content;
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
     * @return content
     */
    public String getContent() {
        return content;
    }

    /**
     * 设置
     * @param content
     */
    public void setContent(String content) {
        this.content = content;
    }

    public String toString() {
        return "LogParam{logId = " + logId + ", content = " + content + "}";
    }
}
