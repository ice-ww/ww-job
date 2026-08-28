package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author 王威
 * @version 1.0
 */

public interface JobLogMapper extends BaseMapper<JobLog> {
    @Select("SELECT COUNT(*) FROM job_log WHERE job_id = #{jobId} AND status = 0")
    long countRunning(@Param("jobId") long jobId);

    @Select("""
          SELECT l.id
          FROM job_log l
          JOIN job_info j ON l.job_id = j.id
          WHERE l.status = 0
            AND l.trigger_time < NOW() - INTERVAL (CASE 
              WHEN j.timeout IS NULL OR j.timeout = 0 THEN 60
              ELSE j.timeout END) SECOND 
          """)
    List<Long> selectTimeoutLogIds();

    @Select("SELECT l.* FROM job_log l WHERE l.status IN (2, 3) AND l.handle_time >= #{from}")
    List<JobLog> selectRecentlyFailed(@Param("from") LocalDateTime from);
}
