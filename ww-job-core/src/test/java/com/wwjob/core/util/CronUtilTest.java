package com.wwjob.core.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author 王威
 * @version 1.0
 */
class CronUtilTest {
    @Test
    void nextTimeEveryFiveMinutes() {
        // "0 */5 * * * *" = 每 5 分钟
        long from = epoch("2026-01-01 00:03:00");
        long next = CronUtil.nextTime("0 */5 * * * *", from);
        assertEquals(epoch("2026-01-01 00:05:00"), next);
    }

    @Test
    void nextTimeEverySecond() {
        long from = epoch("2026-01-01 00:00:00");
        long next = CronUtil.nextTime("* * * * * *", from);
        assertEquals(from + 1000, next);
    }

    @Test
    void invalidCronThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> CronUtil.nextTime("not-a-cron", 0L));
    }

    private static long epoch(String s) {
        return java.time.LocalDateTime.parse(s.replace(' ', 'T'))
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
}
