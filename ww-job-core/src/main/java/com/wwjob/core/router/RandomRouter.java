package com.wwjob.core.router;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author 王威
 * @version 1.0
 */
public class RandomRouter implements Router {
    @Override
    public String route(List<String> addresses, long jobId) {
        if (addresses == null || addresses.isEmpty()) return null;
        return addresses.get(ThreadLocalRandom.current().nextInt(addresses.size()));
    }
}
