package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.dto.FailTopItem;
import com.wwjob.admin.entity.JobLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /** 回调收账终态条件窄更新。WHERE status IN (0,3)：运行中→终态；被超时置 3 后迟到回调仍覆盖（D2）。
     *  已终态(1/2)重复回调 → 0 行 → 幂等。blocked=4（item5）无执行器、永不收回调，天然不在 0/3 内。
     *  返回行数供「不存在」判错。 */
    @Update("UPDATE job_log SET status=#{status}, handle_code=#{handleCode}, handle_msg=#{handleMsg}, handle_time=#{handleTime} "
            + "WHERE id=#{id} AND status IN (0, 3)")
    int completeById(@Param("id") long id, @Param("status") int status, @Param("handleCode") int handleCode,
                     @Param("handleMsg") String handleMsg, @Param("handleTime") LocalDateTime handleTime);

    /** 调度侧收尾窄更新：仅当日志仍在运行(0)才置终态。并发回调先落 1/2 → 0 行自动跳过，不覆盖真实结果 */
    @Update("UPDATE job_log SET status=#{status}, handle_code=#{handleCode}, handle_msg=#{handleMsg}, handle_time=#{handleTime} "
            + "WHERE id=#{id} AND status=0")
    int endRunning(@Param("id") long id, @Param("status") int status, @Param("handleCode") int handleCode,
                   @Param("handleMsg") String handleMsg, @Param("handleTime") LocalDateTime handleTime);

    /** 重试改投新地址时精确更新（仅 attempt>0 且地址变化时用） */
    @Update("UPDATE job_log SET executor_address = #{address} WHERE id = #{id}")
    int updateExecutorAddress(@Param("id") long id, @Param("address") String address);
}
