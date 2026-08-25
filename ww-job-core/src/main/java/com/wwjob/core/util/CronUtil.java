package com.wwjob.core.util;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * @author 王威
 * @version 1.0
 */
public final class CronUtil {
    private CronUtil() {}

    public static long nextTime(String cron, long fromMillis) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 cron 表达式: " + cron, e);
        }
        LocalDateTime from = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(fromMillis), ZoneId.systemDefault());
        LocalDateTime next = expression.next(from);
        if (next == null) {
            throw new IllegalArgumentException("该 cron 无后续触发时间: " + cron);
        }
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
