package com.wwjob.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行器配置，对应 yml 里的 wwjob.executor.*
 */
@ConfigurationProperties(prefix = "wwjob.executor")
public class ExecutorProperties {
    /** admin 调度中心地址，逗号分隔 */
    private String adminAddresses;
    /** 执行器 appName，对应 job_group.app_name */
    private String appName;
    /** 执行器自身端口，用于拼 registryValue = IP:port */
    private int port = 8081;
    /** 执行器对外地址（可选）。不配则自动探测本机 IP；建议显式配置以保证稳定 */
    private String address;
    /** 心跳间隔（秒） */
    private int heartbeatIntervalSeconds = 30;

    public String getAdminAddresses() { return adminAddresses; }
    public void setAdminAddresses(String adminAddresses) { this.adminAddresses = adminAddresses; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) { this.heartbeatIntervalSeconds = heartbeatIntervalSeconds; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

}
