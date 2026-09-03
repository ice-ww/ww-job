package com.wwjob.executor.registry;

/**
 * @author 王威
 * @version 1.0
 */

import com.wwjob.core.model.RegistryParam;
import com.wwjob.executor.ExecutorProperties;
import com.wwjob.executor.admin.AdminAddressPool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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

    @PreDestroy
    public void unregister() {
        try {
            // 优雅停机：广播下线（多 admin 每台独立容错；共享 DB，任一台生效即删行）
            adminPool.broadcast("/registry/offline", buildParam());
        } catch (Exception e) {
            System.err.println("unregister failed: " + e.getMessage());
        }
    }

    private void doRegister() {
        try {
            adminPool.broadcast("/registry", buildParam());
        } catch (Exception e) {
            System.err.println("register failed: " + e.getMessage());
        }
    }

    /** 注册/心跳/下线共用载荷：appName + ip:port（InetAddress 解析失败抛给调用方 try/catch） */
    private RegistryParam buildParam() throws Exception {
        String ip = props.getAddress();
        if (ip == null || ip.isEmpty()) {
            ip = InetAddress.getLocalHost().getHostAddress();
        }
        return new RegistryParam(props.getAppName(), ip + ":" + props.getPort());
    }


}
