package com.wwjob.core.router;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author 王威
 * @version 1.0
 */
public class RoundRobinRouter implements Router {
    private final AtomicInteger counter = new AtomicInteger(0);
    @Override
    public String route(List<String> addresses, long jobId) {
        if (addresses == null || addresses.isEmpty()) return null;
        int idx = Math.abs(counter.getAndIncrement() % addresses.size());
        return addresses.get(idx);
    }
}
