package com.mcp.transfer.admin.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.mcp.transfer.dto.AdminUserRequest;
import com.mcp.transfer.dto.AdminUserVO;
import com.mcp.transfer.dto.LoginRequest;
import com.mcp.transfer.dto.LoginResult;
import com.mcp.transfer.dto.Result;
import com.mcp.transfer.admin.service.AdminAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台登录与用户管理接口
 */
@Slf4j
@Tag(name = "认证与用户")
@RestController
@RequestMapping("/mcp/admin")
public class AdminAuthController {

    @Autowired
    private AdminAuthService adminAuthService;

    /** 登录（放行） */
    @Operation(summary = "管理员登录")
    @PostMapping("/auth/login")
    public Result<LoginResult> login(@RequestBody LoginRequest request, javax.servlet.http.HttpServletRequest httpRequest) {
        return adminAuthService.login(request, com.mcp.transfer.utils.IpUtils.clientIp(httpRequest));
    }

    /** 用户列表 */
    @Operation(summary = "用户列表")
    @GetMapping("/users")
    public Result<List<AdminUserVO>> listUsers() {
        return Result.success(adminAuthService.listUsers());
    }

    /** 创建用户 */
    @Operation(summary = "创建用户")
    @PostMapping("/users")
    public Result<AdminUserVO> createUser(@RequestBody AdminUserRequest request) {
        return Result.success(adminAuthService.createUser(request));
    }

    /** 更新用户 */
    @Operation(summary = "更新用户")
    @PutMapping("/users/{id}")
    public Result<AdminUserVO> updateUser(@PathVariable Long id, @RequestBody AdminUserRequest request) {
        return Result.success(adminAuthService.updateUser(id, request));
    }

    /** 删除用户 */
    @Operation(summary = "删除用户")
    @DeleteMapping("/users/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        adminAuthService.deleteUser(id);
        return Result.success();
    }

    /** 当前登录用户修改自己的密码 */
    @Operation(summary = "修改自己的密码")
    @PostMapping("/users/change-password")
    public Result<Void> changePassword(@RequestBody java.util.Map<String, String> body) {
        com.mcp.transfer.admin.security.AdminAuthContext ctx = com.mcp.transfer.admin.security.AdminAuthContext.get();
        if (ctx == null || ctx.getUserId() == null) {
            return Result.fail("未登录");
        }
        adminAuthService.changePassword(ctx.getUserId(), body.get("oldPassword"), body.get("newPassword"));
        return Result.success();
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("[MCP_ADMIN_AUTH] 处理异常", e);
        if (e instanceof IllegalArgumentException) {
            return Result.fail(e.getMessage());
        }
        return Result.fail("服务器内部错误，请联系管理员");
    }
}
