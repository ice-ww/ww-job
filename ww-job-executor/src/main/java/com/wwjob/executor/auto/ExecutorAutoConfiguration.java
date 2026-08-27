package com.wwjob.executor.auto;

import com.wwjob.executor.ExecutorProperties;
import com.wwjob.executor.callback.CallbackReporter;
import com.wwjob.executor.registry.ExecutorRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    /** 任务执行线程池：有界队列 + AbortPolicy，满则拒绝快速失败（/run 返回"执行器繁忙"） */
    @Bean
    public ExecutorService jobExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(cores, cores * 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                r -> { Thread t = new Thread(r, "ww-job-executor-runner"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public CallbackReporter callbackReporter(ExecutorProperties props) {
        return new CallbackReporter(props);
    }
}
