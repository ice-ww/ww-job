package com.wwjob.admin.service;

/**
 * @author 王威
 * @version 1.0
 */

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 在线执行器内存缓存：由本 admin 收到的注册/心跳/下线广播驱动（写穿），供 route 读，稳态 0 DB。
 * 无 DB、无自身线程；值 = LocalDateTime（admin JVM 时钟域，与 DB heartbeat_time 写入、RegistryCleaner 阈值同域）。
 * 非权威：读侧判「组内无新鲜」必须由调用方回退 DB（B-2），本类只做内存快照。
 * 并发：ConcurrentHashMap；online/prune 期间并发 touch 插入的必为新值（> cutoff），不会被误清。
 */

@Service
public class RegistryCacheService {
    /** jobGroupId -> (registryValue -> 最近心跳时刻) */
    private final ConcurrentMap<Long, ConcurrentMap<String, LocalDateTime>> cache = new ConcurrentHashMap<>();

    /** DB upsert 成功后调用，值用与 DB 写入同一 now。 */
    public void touch(long jobGroupId, String registryValue, LocalDateTime heartbeatTime) {
        cache.computeIfAbsent(jobGroupId, k -> new ConcurrentHashMap<>()).put(registryValue, heartbeatTime);
    }

    /** DB offline 删行成功后调用：优雅下线立即从路由剔除。 */
    public void remove(long jobGroupId, String registryValue) {
        ConcurrentMap<String, LocalDateTime> group = cache.get(jobGroupId);
        if (group != null) {
            group.remove(registryValue);
        }
    }

    /** 新鲜（heartbeatTime >= cutoff）的在线地址，registry_value 字典序（跨实例/重启确定性，FIRST 语义裁定）。
     *  无新鲜 → 空列表（调用方据此回退 DB 复核）。 */
    public List<String> online(long jobGroupId, LocalDateTime cutoff) {
        ConcurrentMap<String, LocalDateTime> group = cache.get(jobGroupId);
        if (group == null || group.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> fresh = new ArrayList<>();
        for (Map.Entry<String, LocalDateTime> e : group.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBefore(cutoff)) {
                fresh.add(e.getKey());
            }
        }
        fresh.sort(Comparator.naturalOrder());
        return fresh;
    }

    /** 清扫 cutoff 前的陈旧项（内存有界；读侧懒过滤才是权威）。返回清理项数。 */
    public int prune(LocalDateTime cutoff) {
        int removed = 0;
        for (Map.Entry<Long, ConcurrentMap<String, LocalDateTime>> g : cache.entrySet()) {
            ConcurrentMap<String, LocalDateTime> group = g.getValue();
            int before = group.size();
            group.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBefore(cutoff));
            removed += before - group.size();
            if (group.isEmpty()) {
                cache.remove(g.getKey(), group);
            }
        }
        return removed;
    }
}
