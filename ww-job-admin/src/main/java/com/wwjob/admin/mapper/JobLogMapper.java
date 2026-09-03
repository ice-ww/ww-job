package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.dto.FailTopItem;
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
            AND l.trigger_time < #{now} - INTERVAL (CASE 
              WHEN j.timeout IS NULL OR j.timeout = 0 THEN 60
              ELSE j.timeout END) SECOND 
          """)
    List<Long> selectTimeoutLogIds(@Param("now") LocalDateTime now);

    // 2失败 3超时未知；4被阻塞(handle_time=null)不入告警——谓词故意只收 2/3，语义见 spec A3
    @Select("SELECT l.* FROM job_log l WHERE l.status IN (2, 3) AND l.handle_time >= #{from}")
    List<JobLog> selectRecentlyFailed(@Param("from") LocalDateTime from);

    @Select("SELECT COUNT(*) FROM job_log WHERE trigger_time >= #{from}")
    long countSince(@Param("from") LocalDateTime from);

    @Select("SELECT COUNT(*) FROM job_log WHERE trigger_time >= #{from} AND status = #{status}")
    long countByStatus(@Param("from") LocalDateTime from, @Param("status") int status);

    @Select("""
            SELECT l.job_id AS jobId, j.job_name AS jobName, l.handler_name AS handlerName, COUNT(*) AS failCount
            FROM job_log l JOIN job_info j ON l.job_id = j.id
            WHERE l.trigger_time >= #{from} AND l.status = 2
            GROUP BY l.job_id, j.job_name, l.handler_name
            ORDER BY failCount DESC
            LIMIT 5
            """)
    List<FailTopItem> selectFailTop(@Param("from") LocalDateTime from);

}
