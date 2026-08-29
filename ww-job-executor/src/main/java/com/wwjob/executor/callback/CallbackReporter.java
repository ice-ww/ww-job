package com.wwjob.executor.callback;

/**
 * @author 王威
 * @version 1.0
 */

import com.wwjob.core.model.CallbackParam;
import com.wwjob.executor.admin.AdminAddressPool;

/**
 * 结果上报器：把执行结果回调给 admin 的 /callback。
 * 失败退避重试 3 次（0/2/5s），全失败打警告——真实结果只能靠 admin 巡检兜底标未知。
 */
public class CallbackReporter {
    /** 每次尝试前的退避毫秒（0/2/5s，共 3 次尝试，最后一次尝试后全失败则放弃） */
    private static final long[] BACKOFF_MS = {0, 2000, 5000};

    private final AdminAddressPool adminPool;

    public CallbackReporter(AdminAddressPool adminPool) {
        this.adminPool = adminPool;
    }

    public void report(CallbackParam param) {
        for (int attempt = 0; attempt < BACKOFF_MS.length; attempt++) {
            if (adminPool.failover("/callback", param)) {
                return;
            }
            if (attempt < BACKOFF_MS.length - 1) {
                try {
                    Thread.sleep(BACKOFF_MS[attempt + 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                System.err.println("callback failed after retries, logId=" + param.getLogId()
                        + ": all admins unreachable");
            }
        }
    }
}
