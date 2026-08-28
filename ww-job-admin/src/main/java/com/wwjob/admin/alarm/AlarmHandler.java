package com.wwjob.admin.alarm;

/**
 * @author 王威
 * @version 1.0
 */
public interface AlarmHandler {
    /** 发送告警。alarmConfig 由各渠道自行解析（邮件 = 逗号分隔邮箱）。失败抛异常 */
    void send(String alarmConfig, String title, String content) throws Exception;
}
