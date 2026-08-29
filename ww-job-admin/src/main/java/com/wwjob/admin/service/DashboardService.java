package com.wwjob.admin.service;

import com.wwjob.admin.dto.DashboardStats;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.admin.mapper.JobRegistryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */

@Service
public class DashboardService {
    private final JobInfoMapper jobInfoMapper;
    private final JobRegistryMapper registryMapper;
    private final JobLogMapper logMapper;

    public DashboardService(JobInfoMapper jobInfoMapper, JobRegistryMapper registryMapper, JobLogMapper logMapper) {
        this.jobInfoMapper = jobInfoMapper;
        this.registryMapper = registryMapper;
        this.logMapper = logMapper;
    }

    public DashboardStats stats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime onlineThreshold = LocalDateTime.now().minusSeconds(90);
        long jobTotal = jobInfoMapper.countAll();
        long jobEnabled = jobInfoMapper.countEnabled();
        DashboardStats s = new DashboardStats();
        s.setJobTotal(jobTotal);
        s.setJobEnabled(jobEnabled);
        s.setJobDisabled(jobTotal - jobEnabled);
        s.setExecutorTotal(registryMapper.countAll());
        s.setExecutorOnline(registryMapper.countOnline(onlineThreshold));
        s.setLogTotalToday(logMapper.countSince(todayStart));
        s.setLogSuccessToday(logMapper.countByStatus(todayStart, JobLog.STATUS_SUCCESS));
        s.setLogFailToday(logMapper.countByStatus(todayStart, JobLog.STATUS_FAIL));
        s.setLogUnknownToday(logMapper.countByStatus(todayStart, JobLog.STATUS_UNKNOWN));
        s.setFailTop(logMapper.selectFailTop(todayStart));
        return s;
    }
}