package com.wwjob.executor.registry;

/**
 * @author 王威
 * @version 1.0
 */

import com.wwjob.core.model.RegistryParam;
import com.wwjob.executor.ExecutorProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;

/**
 * 执行器注册器：启动时注册 + 定时心跳，向 admin 的 /registry 上报。
 */
public class ExecutorRegistry {
    private final ExecutorProperties props;
    private final RestTemplate restTemplate = new RestTemplate();

    public ExecutorRegistry(ExecutorProperties props) { this.props = props; }

    @PostConstruct
    public void register() { doRegister(); }

    @Scheduled(fixedRateString = "#{${wwjob.executor.heartbeat-interval-seconds:30} * 1000}")
    public void heartbeat() { doRegister(); }

    private void doRegister() {
        try {
            String value = InetAddress.getLocalHost().getHostAddress() + ":" + props.getPort();
            RegistryParam param = new RegistryParam();
            param.setRegistryKey(props.getAppName());
            param.setRegistryValue(value);
            for (String admin : props.getAdminAddresses().split(",")) {
                restTemplate.postForObject(admin + "/registry", param, Object.class);
            }
        } catch (Exception e) {
            System.err.println("register failed: " + e.getMessage());
        }
    }
}
