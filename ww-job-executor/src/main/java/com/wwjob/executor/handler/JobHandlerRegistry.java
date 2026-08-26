package com.wwjob.executor.handler;

import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.handler.JobHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 王威
 * @version 1.0
 */
@Component
public class JobHandlerRegistry {
    private final ConcurrentHashMap<String, IJobHandler> handlers = new ConcurrentHashMap<>();

    @Autowired
    public JobHandlerRegistry(ApplicationContext ctx) {
        for (String name : ctx.getBeanNamesForAnnotation(JobHandler.class)) {
            Object bean = ctx.getBean(name);
            JobHandler ann = ctx.findAnnotationOnBean(name, JobHandler.class);
            handlers.put(ann.value(), (IJobHandler) bean);
        }
    }

    public IJobHandler get(String name) { return handlers.get(name); }
}
