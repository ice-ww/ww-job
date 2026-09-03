package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author 王威
 * @version 1.0
 */

/** 行锁：串行化同一任务的并发触发决策（不同任务的行互不阻塞）。必须在事务内使用。 */
public interface JobInfoMapper extends BaseMapper<JobInfo> {
    @Select("SELECT * FROM job_info WHERE id = #{id} FOR UPDATE")
    JobInfo selectByIdForUpdate(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM job_info")
    long countAll();

    @Select("SELECT COUNT(*) FROM job_info WHERE trigger_status = 1")
    long countEnabled();

    /** 精确只更新触发时间列，不整实体写回，避免覆盖并发 /stop 写的 trigger_status=0（F2-9） */
    @Update("UPDATE job_info SET trigger_last_time = #{lastTime} WHERE id = #{id}")
    int touchLastTime(@Param("id") long id, @Param("lastTime") long lastTime);

}
