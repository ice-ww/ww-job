package com.wwjob.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.wwjob.admin.mapper")
public class WwJobAdminApplication {
    public static void main(String[] args) {

        SpringApplication.run(WwJobAdminApplication.class, args);
    }
}
