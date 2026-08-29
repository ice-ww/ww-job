package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobLock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王威
 * @version 1.0
 */

public interface JobLockMapper extends BaseMapper<JobLock> {
    /** 分布式锁：FOR UPDATE 拿锁，事务提交才释放（阻塞式互斥）。必须在事务内使用 */
    @Select("SELECT * FROM job_lock WHERE lock_name = #{lockName} FOR UPDATE")
    JobLock selectForUpdate(@Param("lockName") String lockName);
}
