package com.wwjob.executor.registry;

/**
 * @author 王威
 * @version 1.0
 */

import com.wwjob.core.model.RegistryParam;
import com.wwjob.executor.ExecutorProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;

/**
 * 执行器注册器：启动时注册 + 定时心跳，向 admin 的 /registry 上报。
 */
public class ExecutorRegistry {
    private final ExecutorProperties props;
    private final RestTemplate restTemplate;

    public ExecutorRegistry(ExecutorProperties props) {
        this.props = props;
        // @Scheduled 默认单线程：心跳若无限阻塞，后续心跳全部停摆，执行器会被 90s 后误判下线。故加超时
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
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
            for (String admin : props.getAdminAddresses().split(",")) {
                restTemplate.postForObject(admin + "/registry", param, Object.class);
            }
        } catch (Exception e) {
            System.err.println("register failed: " + e.getMessage());
        }
    }

}
