package com.wwjob.core.model;

/**
 * @author 王威
 * @version 1.0
 */
public class CallbackParam {
    private long logId;
    private int handleCode;
    private String handleMsg;
    private long handleTime;

    public CallbackParam() {
    }

    public CallbackParam(long logId, int handleCode, String handleMsg, long handleTime) {
        this.logId = logId;
        this.handleCode = handleCode;
        this.handleMsg = handleMsg;
        this.handleTime = handleTime;
    }

    public long getLogId() {
        return logId;
    }

    public void setLogId(long logId) {
        this.logId = logId;
    }

    public int getHandleCode() {
        return handleCode;
    }

    public void setHandleCode(int handleCode) {
        this.handleCode = handleCode;
    }

    public String getHandleMsg() {
        return handleMsg;
    }

    public void setHandleMsg(String handleMsg) {
        this.handleMsg = handleMsg;
    }

    public long getHandleTime() {
        return handleTime;
    }

    public void setHandleTime(long handleTime) {
        this.handleTime = handleTime;
    }

    @Override
    public String toString() {
        return "CallbackParam{logId = " + logId + ", handleCode = " + handleCode + ", handleMsg = " + handleMsg + ", handleTime = " + handleTime + "}";
    }
}
