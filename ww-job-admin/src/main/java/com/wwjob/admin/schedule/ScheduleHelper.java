package com.wwjob.admin.schedule;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.service.JobTriggerService;
import com.wwjob.core.schedule.TimeWheel;
import com.wwjob.core.util.CronUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author 王威
 * @version 1.0
 */
@Component
public class ScheduleHelper {
    private static final long PRE_READ_MS = 5000;
    private static final long TICK_MS = 1000;
    private static final int WHEEL_SIZE = 60;

    private final JobInfoMapper jobInfoMapper;
    private final JobTriggerService triggerService;
    private final TimeWheel timeWheel = new TimeWheel(TICK_MS, WHEEL_SIZE);
    /** 已放入时间轮等待触发的任务 id，防止同一任务被重复入轮 */
    private final Set<Long> scheduledJobIds = ConcurrentHashMap.newKeySet();
    /** 触发线程池：ringThread 只负责从时间轮出队，实际触发（HTTP 调用、DB 更新）在线程池执行，
     *  避免某台执行器挂起时把时间轮整个卡死 */
    private final ExecutorService triggerPool = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2), r -> {
                Thread t = new Thread(r, "ww-job-trigger");
                t.setDaemon(true);
                return t;
            });

    private Thread scheduleThread;
    private Thread ringThread;
    private volatile boolean running = true;

    public ScheduleHelper(JobInfoMapper jobInfoMapper, JobTriggerService triggerService) {
        this.jobInfoMapper = jobInfoMapper;
        this.triggerService = triggerService;
    }

    @PostConstruct
    public void start() {
        scheduleThread = new Thread(this::scheduleLoop, "ww-job-schedule");
        ringThread = new Thread(this::ringLoop, "ww-job-ring");
        scheduleThread.setDaemon(true);
        ringThread.setDaemon(true);
        scheduleThread.start();
        ringThread.start();
    }

    private void scheduleLoop() {
        while (running) {
            try {
                long now = System.currentTimeMillis();
                long windowEnd = now + PRE_READ_MS;
                // 预读：已到期或即将到期（<= now+5s）的启用任务。
                // 不加下界：trigger_next_time 落后的任务也会被捞起，在 scheduleIfNeeded 里追赶，避免永久失活
                var list = jobInfoMapper.selectList(new QueryWrapper<JobInfo>()
                        .eq("trigger_status", 1)
                        .le("trigger_next_time", windowEnd));
                for (JobInfo job : list) {
                    scheduleIfNeeded(job, now);
                }
            } catch (Exception e) {
                // 单次扫描异常不退出循环
            }
            sleep(TICK_MS);
        }
    }

    /** 同一任务已在时间轮中则跳过，避免重复触发；否则入轮。落后任务先推进到未来再入轮 */
    private void scheduleIfNeeded(JobInfo job, long now) {
        if (!scheduledJobIds.add(job.getId())) {
            return;
        }
        long next = job.getTriggerNextTime();
        if (next < now) {
            // 任务落后（如 admin 重启、上次推进失败）：直接跳到下一个未来触发点。
            // 这样既不会立即补触发，也不会因 query 只查未来而永久失活
            next = CronUtil.nextTime(job.getCron(), now);
            job.setTriggerNextTime(next);
            jobInfoMapper.updateById(job);
        }
        long delay = Math.max(0, next - now);
        timeWheel.addTask(delay, () -> {
            try {
                triggerService.trigger(job.getId(), "cron");
            } finally {
                // 触发后推进下次触发时间，保证任务不会因 trigger_next_time 过期而失活
                advanceNextTime(job.getId(), job.getCron());
                scheduledJobIds.remove(job.getId());
            }
        });
    }

    /** 触发完成后，把下次触发时间算好写回 DB */
    private void advanceNextTime(long jobId, String cron) {
        try {
            JobInfo job = jobInfoMapper.selectById(jobId);
            if (job == null || job.getTriggerStatus() == null || job.getTriggerStatus() != 1) {
                return;  // 任务被停用则不再推进，让它自然失活
            }
            long next = CronUtil.nextTime(cron, System.currentTimeMillis());
            job.setTriggerNextTime(next);
            jobInfoMapper.updateById(job);
        } catch (Exception e) {
            // 推进失败，下次扫描会重新调度
        }
    }

    private void ringLoop() {
        while (running) {
            try {
                for (Runnable task : timeWheel.advance()) {
                    triggerPool.execute(task);
                }
            } catch (Exception e) {
                // 忽略单次异常
            }
            sleep(TICK_MS);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @PreDestroy
    public void stop() {
        running = false;
        triggerPool.shutdownNow();
        scheduleThread.interrupt();
        ringThread.interrupt();
    }
}
