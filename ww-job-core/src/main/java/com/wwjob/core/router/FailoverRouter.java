package com.wwjob.core.router;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author 王威
 * @version 1.0
 */
public class FailoverRouter implements Router {
    private final AtomicInteger counter = new AtomicInteger(0);
    @Override
    public String route(List<String> addresses, long jobId) {
        if (addresses == null || addresses.isEmpty()) return null;
        int size = addresses.size();
        for (int i = 0; i < size; i++) {
            int idx = Math.abs(counter.getAndIncrement() % size);
            // 调用方会在失败后从列表移除该地址，因此这里直接返回；重复轮询由外层负责
            return addresses.get(idx);
        }
        return null;
    }
}
