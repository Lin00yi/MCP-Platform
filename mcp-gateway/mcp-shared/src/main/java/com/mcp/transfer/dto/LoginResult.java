package com.mcp.transfer.dto;

import lombok.Data;

/**
 * 后台登录结果
 */
@Data
public class LoginResult {
    private String token;
    private AdminUserVO user;
}
