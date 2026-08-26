package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobRegistry;
import com.wwjob.admin.mapper.JobGroupMapper;
import com.wwjob.admin.mapper.JobRegistryMapper;
import com.wwjob.core.router.FailoverRouter;
import com.wwjob.core.router.RandomRouter;
import com.wwjob.core.router.RoundRobinRouter;
import com.wwjob.core.router.Router;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author 王威
 * @version 1.0
 */
@Service
public class ExecutorRouterService {
    private final JobRegistryMapper registryMapper;

    public ExecutorRouterService(JobGroupMapper groupMapper, JobRegistryMapper registryMapper) {
        this.registryMapper = registryMapper;
    }

    public String route(long jobGroupId, String routeStrategy, long jobId) {
        List<String> addresses = onlineAddresses(jobGroupId);
        Router router = switch (routeStrategy) {
            case "random" -> new RandomRouter();
            case "failover" -> new FailoverRouter();
            default -> new RoundRobinRouter();
        };
        return router.route(addresses, jobId);
    }

    public List<String> onlineAddresses(long jobGroupId) {
        return registryMapper.selectList(new QueryWrapper<JobRegistry>()
                        .eq("job_group_id", jobGroupId))
                .stream().map(JobRegistry::getRegistryValue)
                .collect(Collectors.toList());
    }
}
