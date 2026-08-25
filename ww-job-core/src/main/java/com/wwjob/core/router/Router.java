package com.wwjob.core.router;

import java.util.List;

/**
 * @author 王威
 * @version 1.0
 */
public interface Router {
    String route(List<String> addresses, long jobId);
}
