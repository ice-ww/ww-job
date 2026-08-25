package com.wwjob.executor.auto;

import com.wwjob.executor.ExecutorProperties;
import com.wwjob.executor.registry.ExecutorRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 执行器自动配置：Spring Boot 启动时通过 imports 文件加载。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ExecutorProperties.class)
@ConditionalOnProperty(prefix = "wwjob.executor", name = "app-name")
public class ExecutorAutoConfiguration {

    @Bean
    public ExecutorRegistry executorRegistry(ExecutorProperties props) {
        return new ExecutorRegistry(props);
    }
}
