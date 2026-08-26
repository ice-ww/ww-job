package com.wwjob.executor.samples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.wwjob.executor.samples", "com.wwjob.executor"})
public class WwJobExecutorSamplesApplication {

    public static void main(String[] args) {
        SpringApplication.run(WwJobExecutorSamplesApplication.class, args);
    }
}
