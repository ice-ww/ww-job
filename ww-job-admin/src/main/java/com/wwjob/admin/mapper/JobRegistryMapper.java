package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobRegistry;
import org.apache.ibatis.annotations.Insert;
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

    /** 原子 upsert：唯一键 (job_group_id, registry_value) 命中则刷新心跳，未命中则插入。并发心跳归一行。 */
    @Insert("INSERT INTO job_registry (job_group_id, registry_key, registry_value, heartbeat_time) "
            + "VALUES (#{jobGroupId}, #{registryKey}, #{registryValue}, #{heartbeatTime}) "
            + "ON DUPLICATE KEY UPDATE heartbeat_time = VALUES(heartbeat_time), registry_key = VALUES(registry_key)")
    int upsert(@Param("jobGroupId") long jobGroupId, @Param("registryKey") String registryKey,
               @Param("registryValue") String registryValue, @Param("heartbeatTime") LocalDateTime heartbeatTime);
}
