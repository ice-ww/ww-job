package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王威
 * @version 1.0
 */

public interface JobLogMapper extends BaseMapper<JobLog> {
    @Select("SELECT COUNT(*) FROM job_log WHERE job_id = #{jobId} AND status = 0")
    long countRunning(@Param("jobId") long jobId);

}
