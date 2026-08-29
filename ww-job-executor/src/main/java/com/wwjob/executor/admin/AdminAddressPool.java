package com.wwjob.executor.admin;

/**
 * @author 王威
 * @version 1.0
 */

import com.wwjob.executor.ExecutorProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 与 admin 通信的统一入口：地址列表 + 路由策略 + 故障切换游标。
 * broadcast —— 广播给所有 admin（每台独立容错），用于注册/心跳；
 * failover  —— 从游标起逐个尝试，第一台成功记为游标，用于回调。
 */
public class AdminAddressPool {
    private final List<String> admins;
    private final RestTemplate restTemplate;
    /** 失败切换游标：只记「最近一次成功」的 admin 下标 */
    private final AtomicInteger index = new AtomicInteger(0);

    public AdminAddressPool(ExecutorProperties props) {
        this.admins = parse(props.getAdminAddresses());
        if (admins.isEmpty()) {
            throw new IllegalArgumentException("wwjob.executor.admin-addresses 未配置任何 admin 地址");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    private List<String> parse(String adminAddresses) {
        List<String> list = new ArrayList<>();
        if (adminAddresses == null) return list;
        for (String s : adminAddresses.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }

    /** 广播：发给所有 admin，单台失败不影响其它。返回成功台数（0=全部失败） */
    public int broadcast(String path, Object body) {
        int ok = 0;
        for (String admin : admins) {
            try {
                restTemplate.postForObject(admin + path, body, Object.class);
                ok++;
            } catch (Exception e) {
                System.err.println("broadcast failed: " + admin + path + " -> " + e.getMessage());
            }
        }
        return ok;
    }

    /** 故障切换：从游标起逐个尝试，成功记游标返回 true；全失败返回 false */
    public boolean failover(String path, Object body) {
        int n = admins.size();
        for (int i = 0; i < n; i++) {
            int idx = (index.get() + i) % n;
            try {
                restTemplate.postForObject(admins.get(idx) + path, body, Object.class);
                index.set(idx);
                return true;
            } catch (Exception e) {
                // 这台失败，试下一台
            }
        }
        return false;
    }
}
