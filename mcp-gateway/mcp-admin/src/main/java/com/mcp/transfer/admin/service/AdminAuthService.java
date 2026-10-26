package com.mcp.transfer.admin.service;

import com.mcp.transfer.admin.security.AdminAuthContext;
import com.mcp.transfer.shared.dto.AdminUserRequest;
import com.mcp.transfer.shared.dto.AdminUserVO;
import com.mcp.transfer.shared.dto.LoginRequest;
import com.mcp.transfer.shared.dto.LoginResult;
import com.mcp.transfer.shared.dto.Result;

import java.util.List;

/**
 * 后台管理认证与用户管理
 */
public interface AdminAuthService {

    Result<LoginResult> login(LoginRequest request, String clientIp);

    /** 验签并填充 tenantIds，失败返回 null */
    AdminAuthContext verifyToken(String token);

    List<AdminUserVO> listUsers();

    AdminUserVO createUser(AdminUserRequest request);

    AdminUserVO updateUser(Long id, AdminUserRequest request);

    void deleteUser(Long id);

    /** 当前登录用户修改自己的密码 */
    void changePassword(Long userId, String oldPassword, String newPassword);
}
