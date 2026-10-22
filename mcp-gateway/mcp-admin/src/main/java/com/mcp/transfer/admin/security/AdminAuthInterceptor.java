package com.mcp.transfer.admin.security;

import com.alibaba.fastjson.JSON;
import com.mcp.transfer.admin.security.AdminAuthContext;
import com.mcp.transfer.enums.RoleEnum;
import com.mcp.transfer.admin.service.AdminAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * 后台管理鉴权拦截器：
 * 1. 对 /mcp/admin/** 与 /mcp/log/** 强制登录（JWT）并做角色权限校验；
 * 2. 登录接口 /mcp/admin/auth/login 放行；
 * 3. 权限判断统一收敛到 {@link AdminPermissionEvaluator}，<b>默认拒绝</b>；
 * 4. 动态 MCP 端点不在此拦截，走 MCP 协议鉴权。
 */
@Slf4j
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Autowired
    private AdminAuthService adminAuthService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();

        // 仅拦截管理后台 / 日志接口
        boolean isAdminPath = path.startsWith("/mcp/admin");
        boolean isLogPath = path.startsWith("/mcp/log");
        if (!isAdminPath && !isLogPath) {
            return true;
        }
        if (AdminPermissionEvaluator.isPublic(request)) {
            return true;
        }

        String token = null;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        AdminAuthContext ctx = adminAuthService.verifyToken(token);
        if (ctx == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "未登录或登录已过期");
            return false;
        }

        RoleEnum role = ctx.getRoleEnum();
        if (!AdminPermissionEvaluator.check(request.getMethod(), path, role)) {
            log.warn("[ADMIN_AUTH] 拒绝访问 method={}, path={}, user={}, role={}",
                    request.getMethod(), path, ctx.getUsername(), ctx.getRole());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, 403, "无权限执行此操作");
            return false;
        }

        AdminAuthContext.set(ctx);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AdminAuthContext.clear();
    }

    private void writeError(HttpServletResponse response, int httpStatus, int code, String message) throws Exception {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("message", message);
        PrintWriter writer = response.getWriter();
        writer.write(JSON.toJSONString(body));
        writer.flush();
        writer.close();
    }
}
