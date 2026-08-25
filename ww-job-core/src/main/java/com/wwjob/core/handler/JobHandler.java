package com.wwjob.core.handler;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * @author 王威
 * @version 1.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface JobHandler {
    /** handler 名称，调度中心以此定位执行器上的任务。 */
    String value();
}
