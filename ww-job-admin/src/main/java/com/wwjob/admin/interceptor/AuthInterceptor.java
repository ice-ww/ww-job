package com.wwjob.admin.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @author 王威
 * @version 1.0
 */

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${wwjob.auth.jwt-secret}")
    private String jwtSecret;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            String token = auth.substring(BEARER_PREFIX.length());
            String username = JwtUtil.parseToken(token, jwtSecret);
            if (username != null) {
                request.setAttribute("username", username);
                return true;
            }
        }
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(new ObjectMapper().writeValueAsString(new ReturnT<>(401, "未登录或登录已过期")));
        return false;
    }
}
