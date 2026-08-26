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
                // 预读：trigger_next_time 在 [now, now+5s] 内的启用任务
                var list = jobInfoMapper.selectList(new QueryWrapper<JobInfo>()
                        .eq("trigger_status", 1)
                        .ge("trigger_next_time", now)
                        .le("trigger_next_time", windowEnd));
                for (JobInfo job : list) {
                    refreshNextTime(job, now);
                }
            } catch (Exception e) {
                // 单次扫描异常不退出循环
            }
            sleep(TICK_MS);
        }
    }

    private void ringLoop() {
        while (running) {
            try {
                for (Runnable task : timeWheel.advance()) {
                    task.run();
                }
            } catch (Exception e) {
                // 忽略单次异常
            }
            sleep(TICK_MS);
        }
    }

    private void refreshNextTime(JobInfo job, long now) {
        long next = CronUtil.nextTime(job.getCron(), now);   // 用 cron 算下次时间
        job.setTriggerNextTime(next);
        jobInfoMapper.updateById(job);                        // 更新回 DB
        long delay = next - System.currentTimeMillis();       // 距下次还有多久
        timeWheel.addTask(Math.max(0, delay), () -> triggerService.trigger(job.getId(), "cron"));  // 放时间轮
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @PreDestroy
    public void stop() {
        running = false;
        scheduleThread.interrupt();
        ringThread.interrupt();
    }
}
