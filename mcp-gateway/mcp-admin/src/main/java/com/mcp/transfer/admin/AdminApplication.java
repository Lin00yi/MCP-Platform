package com.mcp.transfer.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * S2 控制面启动类：只加载 {@code /mcp/admin/**} 与 {@code /mcp/log/**} 相关的 Bean。
 *
 * <p>与数据面（{@code com.mcp.transfer.McpTransferApplication}）共用 {@code mcp-shared} 内核，
 * 但两者不互相依赖：控制面通过 {@code port} 包下的端口（HTTP 适配器）访问数据面能力。
 */
@MapperScan("com.mcp.transfer.shared.mapper")
@SpringBootApplication(scanBasePackages = "com.mcp.transfer")
@EnableScheduling
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
