package com.wwjob.core.schedule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeWheelTest {
    // 一圈 10 格，每格 100ms，整圈 1000ms
    private TimeWheel newWheel() { return new TimeWheel(100, 10); }

    @Test
    void taskWithinOneTickFiresOnNextAdvance() {
        TimeWheel wheel = newWheel();
        List<String> fired = new ArrayList<>();
        wheel.addTask(50, () -> fired.add("a"));
        run(wheel.advance()); // 延迟 50ms < 1 格(100ms)，第一次 advance 返回到期任务并执行
        assertEquals(List.of("a"), fired);
    }

    @Test
    void taskFiresAfterExactNumberOfTicks() {
        TimeWheel wheel = newWheel();
        List<String> fired = new ArrayList<>();
        wheel.addTask(300, () -> fired.add("x"));
        for (int i = 0; i < 3; i++) run(wheel.advance()); // 推进 3 次(300ms)后触发
        assertEquals(List.of("x"), fired);
    }

    @Test
    void taskWithExactlyOneRotationDelayFiresAfterFullRotation() {
        TimeWheel wheel = newWheel();
        List<String> fired = new ArrayList<>();
        wheel.addTask(1000, () -> fired.add("full")); // 恰好一圈
        for (int i = 0; i < 10; i++) run(wheel.advance());
        assertEquals(List.of("full"), fired); // 正好第 10 格触发，不多不少
    }

    @Test
    void taskBeyondOneRotationUsesRemainingRounds() {
        TimeWheel wheel = newWheel();
        List<String> fired = new ArrayList<>();
        wheel.addTask(1200, () -> fired.add("beyond"));
        for (int i = 0; i < 12; i++) run(wheel.advance());
        assertEquals(List.of("beyond"), fired); // 第 12 格才触发
    }

    /** 执行 advance() 返回的到期任务（副作用写入 fired 列表）。 */
    private static void run(List<Runnable> tasks) {
        for (Runnable t : tasks) t.run();
    }
}
