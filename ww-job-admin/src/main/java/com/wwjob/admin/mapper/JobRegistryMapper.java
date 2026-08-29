package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobRegistry;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */

public interface JobRegistryMapper extends BaseMapper<JobRegistry> {
    @Select("SELECT COUNT(*) FROM job_registry")
    long countAll();

    @Select("SELECT COUNT(*) FROM job_registry WHERE heartbeat_time >= #{threshold}")
    long countOnline(@Param("threshold")LocalDateTime threshold);
}
