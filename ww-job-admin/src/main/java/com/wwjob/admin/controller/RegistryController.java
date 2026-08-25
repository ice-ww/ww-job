package com.wwjob.admin.controller;

import com.wwjob.admin.service.RegistryService;
import com.wwjob.core.model.RegistryParam;
import com.wwjob.core.model.ReturnT;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 王威
 * @version 1.0
 */
@RestController
public class RegistryController {
    private final RegistryService registryService;
    public RegistryController(RegistryService registryService) { this.registryService = registryService; }

    @PostMapping("/registry")
    public ReturnT<String> registry(@RequestBody RegistryParam param) {
        return registryService.registry(param);
    }

    @PostMapping("/heartbeat")
    public ReturnT<String> heartbeat(@RequestBody RegistryParam param) {
        return registryService.heartbeat(param);
    }
}
