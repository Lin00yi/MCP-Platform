package com.mcp.transfer.shared.dto;

import lombok.Data;

/**
 * 后台登录结果
 */
@Data
public class LoginResult {
    private String token;
    private AdminUserVO user;
}
