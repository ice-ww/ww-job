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

    public TimeWheel(long tickDurationMs, int wheelSize) {
        this.tickDurationMs = tickDurationMs;
        this.wheelSize = wheelSize;
        this.slots = new ArrayList<>(wheelSize);
        for (int i = 0; i < wheelSize; i++) {
            this.slots.add(new LinkedList<>());
        }
    }

    public void addTask(long delayMs, Runnable task) {
        long delayTicks = Math.max(1, (delayMs + tickDurationMs - 1) / tickDurationMs);
        int stopIndex = (int) ((currentTick + delayTicks) % wheelSize);
        long remainingRounds = (delayTicks - 1) / wheelSize;
        slots.get(stopIndex).add(new TimerTask(task, remainingRounds));
    }

    public List<Runnable> advance() {
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
