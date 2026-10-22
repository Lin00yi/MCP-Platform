package com.mcp.transfer.dto;

import lombok.Data;

/**
 * 后台登录请求
 */
@Data
public class LoginRequest {
    private String username;
    private String password;
}
