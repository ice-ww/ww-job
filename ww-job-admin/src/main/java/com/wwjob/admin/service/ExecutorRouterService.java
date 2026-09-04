package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobRegistry;
import com.wwjob.admin.mapper.JobRegistryMapper;
import com.wwjob.core.router.FailoverRouter;
import com.wwjob.core.router.RandomRouter;
import com.wwjob.core.router.RoundRobinRouter;
import com.wwjob.core.router.Router;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author 王威
 * @version 1.0
 */
@Service
public class ExecutorRouterService {
    private final JobRegistryMapper registryMapper;
    private final RegistryCacheService registryCacheService;
    /** 路由实例保持为字段（单例）：内部计数器跨调用持续，轮询才能真正轮转。
     *  若每次 route() new 一个，AtomicInteger 恒从 0 开始，永远选中第一个执行器 */
    private final Router roundRobin = new RoundRobinRouter();
    private final Router random = new RandomRouter();
    private final Router failover = new FailoverRouter();

    public ExecutorRouterService(JobRegistryMapper registryMapper, RegistryCacheService registryCacheService) {
        this.registryMapper = registryMapper;
        this.registryCacheService = registryCacheService;
    }

    public String route(long jobGroupId, String routeStrategy, long jobId) {
        List<String> addresses = onlineAddresses(jobGroupId);
        Router router = switch (routeStrategy) {
            case "random" -> random;
            case "failover" -> failover;
            default -> roundRobin;
        };
        return router.route(addresses, jobId);
    }

    public List<String> onlineAddresses(long jobGroupId) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(JobRegistry.ONLINE_SECONDS);
        // 稳态：缓存有新鲜 → 0 DB（B2 目标）
        List<String> cached = registryCacheService.online(jobGroupId, threshold);
        if (!cached.isEmpty()) {
            return cached;
        }
        // B-2：组内无新鲜（冷启动/与执行器分区/全部离线）→ 回退 DB 复核并水合，语义与现状 DB 逐格一致
        List<JobRegistry> rows = registryMapper.selectList(new QueryWrapper<JobRegistry>()
                .eq("job_group_id", jobGroupId)
                .ge("heartbeat_time", threshold));
        if (!rows.isEmpty()) {
            rows.forEach(r -> registryCacheService.touch(jobGroupId, r.getRegistryValue(), r.getHeartbeatTime()));
        }
        return rows.stream().map(JobRegistry::getRegistryValue).collect(Collectors.toList());
    }

}
