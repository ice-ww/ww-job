package com.wwjob.core.schedule;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * @author 王威
 * @version 1.0
 */
public class TimeWheel {
    private final long tickDurationMs;
    private final int wheelSize;
    private final List<List<TimerTask>> slots;
    private int currentTick = 0;

    /**
     * 线程安全说明：
     * 调度线程（addTask）与推进线程（advance）会并发操作同一个时间轮。
     * 两个方法都用 synchronized 串行化，防止数据竞争（丢任务、重复触发、ConcurrentModificationException）。
     * 注意 task 的执行在 advance() 之外，由调用方线程池执行，不会长时间持有锁。
     */

    public TimeWheel(long tickDurationMs, int wheelSize) {
        this.tickDurationMs = tickDurationMs;
        this.wheelSize = wheelSize;
        this.slots = new ArrayList<>(wheelSize);
        for (int i = 0; i < wheelSize; i++) {
            this.slots.add(new LinkedList<>());
        }
    }

    public synchronized void addTask(long delayMs, Runnable task) {
        long delayTicks = Math.max(1, (delayMs + tickDurationMs - 1) / tickDurationMs);
        int stopIndex = (int) ((currentTick + delayTicks) % wheelSize);
        long remainingRounds = (delayTicks - 1) / wheelSize;
        slots.get(stopIndex).add(new TimerTask(task, remainingRounds));
    }

    public synchronized List<Runnable> advance() {
        currentTick = (currentTick + 1) % wheelSize;
        List<TimerTask> bucket = slots.get(currentTick);
        List<Runnable> due = new ArrayList<>();
        Iterator<TimerTask> it = bucket.iterator();
        while (it.hasNext()) {
            TimerTask t = it.next();
            if (t.remainingRounds > 0) {
                t.remainingRounds--;
            } else {
                due.add(t.task);
                it.remove();
            }
        }
        return due;
    }

    public int currentTick() { return currentTick; }

    private static final class TimerTask {
        final Runnable task;
        long remainingRounds;
        TimerTask(Runnable task, long remainingRounds) {
            this.task = task;
            this.remainingRounds = remainingRounds;
        }
    }
}
