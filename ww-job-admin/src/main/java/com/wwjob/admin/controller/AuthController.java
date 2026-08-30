package com.wwjob.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.SysUser;
import com.wwjob.admin.mapper.SysUserMapper;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * @author 王威
 * @version 1.0
 */

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final SysUserMapper sysUserMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${wwjob.auth.jwt-secret}")
    private String jwtSecret;

    @Value("${wwjob.auth.jwt-expire-seconds}")
    private long jwtExpireSeconds;

    public AuthController(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    @PostMapping("/login")
    public ReturnT<Map<String, String>> login(@RequestBody LoginRequest req) {
        SysUser user = sysUserMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", req.getUsername()));
        // 用户不存在 / 禁用 / 密码错 → 统一提示，防用户名枚举
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            return new ReturnT<>(401, "用户名或密码错误");
        }
        String token = JwtUtil.createToken(user.getUsername(), jwtSecret, jwtExpireSeconds);
        Map<String, String> data = new HashMap<>();
        data.put("token", token);
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        return ReturnT.success(data);
    }

    /** 登录请求体 */
    public static class LoginRequest {
        private String username;
        private String password;
        public LoginRequest() {}
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
