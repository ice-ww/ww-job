package com.wwjob.executor.registry;

/**
 * @author 王威
 * @version 1.0
 */

import com.wwjob.core.model.RegistryParam;
import com.wwjob.executor.ExecutorProperties;
import com.wwjob.executor.admin.AdminAddressPool;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.InetAddress;

/**
 * 执行器注册器：启动时注册 + 定时心跳，向 admin 的 /registry 广播（单台失败不影响其他）。
 */
public class ExecutorRegistry {
    private final ExecutorProperties props;
    private final AdminAddressPool adminPool;

    public ExecutorRegistry(ExecutorProperties props, AdminAddressPool adminPool) {
        this.props = props;
        this.adminPool = adminPool;
    }

    @PostConstruct
    public void register() { doRegister(); }

    @Scheduled(fixedRateString = "#{${wwjob.executor.heartbeat-interval-seconds:30} * 1000}")
    public void heartbeat() { doRegister(); }

    private void doRegister() {
        try {
            String ip = props.getAddress();
            if (ip == null || ip.isEmpty()) {
                ip = InetAddress.getLocalHost().getHostAddress();
            }
            String value = ip + ":" + props.getPort();
            RegistryParam param = new RegistryParam();
            param.setRegistryKey(props.getAppName());
            param.setRegistryValue(value);
            adminPool.broadcast("/registry", param);  // 广播到所有 admin，单台失败不影响其它
        } catch (Exception e) {
            System.err.println("register failed: " + e.getMessage());
        }
    }
}
