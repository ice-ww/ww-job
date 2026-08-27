package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王威
 * @version 1.0
 */
public interface JobInfoMapper extends BaseMapper<JobInfo> {

    /** 行锁：串行化同一任务的并发触发决策（不同任务的行互不阻塞）。必须在事务内使用。 */
    @Select("SELECT * FROM job_info WHERE id = #{id} FOR UPDATE")
    JobInfo selectByIdForUpdate(@Param("id") long id);
}
